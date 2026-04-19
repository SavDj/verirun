import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { Navigate } from 'react-router-dom';
import SimulationLogs from './SimulationLogs';
import type { BuildMode, SimulationOptions } from '../types/simulation';

const DEFAULT_DESIGN = '// Enter your Verilog design here\nmodule top();\n  \nendmodule';
const DEFAULT_TESTBENCH = '// Enter your testbench here\nmodule tb();\n  \nendmodule';

const BUILD_MODES: { value: BuildMode; label: string }[] = [
  { value: 'BINARY', label: 'Binary' },
  { value: 'CC_MODEL', label: 'C++ model only' },
  { value: 'LINT_ONLY', label: 'Lint check only' },
];

function loadFromStorage(key: string, fallback: string): string {
  try {
    const stored = localStorage.getItem(key);
    return stored !== null ? stored : fallback;
  } catch {
    return fallback;
  }
}

function saveToStorage(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch (error) {
    const message = error instanceof DOMException && error.name === 'QuotaExceededError'
      ? 'Local storage is full. Your code will not persist across sessions.'
      : 'Failed to save code to local storage.';
    console.warn(message);
  }
}

function parseFlags(value: string): string[] {
  if (!value) return [];
  return value.split(/\s+/).map(s => s.trim()).filter(s => s.length > 0);
}

function extractTraceFst(flags: string[]): boolean {
  return flags.some(f => f === '--trace-fst');
}

function extractTraceVcd(flags: string[]): boolean {
  return flags.some(f => f === '--trace-vcd');
}

const Editor: React.FC = () => {
  const { isAuthenticated, logout } = useAuth();
  const [designCode, setDesignCode] = useState<string>(() => loadFromStorage('verirun-design', DEFAULT_DESIGN));
  const [testbenchCode, setTestbenchCode] = useState<string>(() => loadFromStorage('verirun-testbench', DEFAULT_TESTBENCH));
  const [logs, setLogs] = useState<string[]>([]);
  const [isRunning, setIsRunning] = useState<boolean>(false);
  const [jobId, setJobId] = useState<string | null>(null);
  const [simulationPassed, setSimulationPassed] = useState<boolean>(false);
  const [jobBuildMode, setJobBuildMode] = useState<BuildMode | null>(null);
  const [jobTraceFst, setJobTraceFst] = useState<boolean>(false);
  const [jobTraceVcd, setJobTraceVcd] = useState<boolean>(false);
  const [copied, setCopied] = useState(false);

  const [buildMode, setBuildMode] = useState<BuildMode>('BINARY');
  const [buildFlags, setBuildFlags] = useState('');
  const [runFlags, setRunFlags] = useState('');

  const [logsHeight, setLogsHeight] = useState<number>(() => {
    const saved = localStorage.getItem('verirun-logs-height');
    return saved ? parseInt(saved, 10) : Math.round(window.innerHeight * 0.25);
  });

  const containerRef = useRef<HTMLDivElement>(null);
  const isDragging = useRef(false);


  const startLogsResize = (e: React.MouseEvent) => {
    e.preventDefault();
    isDragging.current = true;
    document.body.style.cursor = 'row-resize';
    document.body.style.userSelect = 'none';
  };

  useEffect(() => {
    const onLogsResizeDrag = (e: MouseEvent) => {
      if (!isDragging.current || !containerRef.current) return;
      e.preventDefault();
      const rect = containerRef.current.getBoundingClientRect();
      const newHeight = rect.bottom - e.clientY;
      const clamped = Math.max(100, Math.min(newHeight, rect.height - 100));
      setLogsHeight(clamped);
    };

    const stopLogsResize = () => {
      if (isDragging.current) {
        isDragging.current = false;
        document.body.style.cursor = '';
        document.body.style.userSelect = '';
      }
    };

    const clampLogsHeightOnWindowResize = () => {
      if (!containerRef.current) return;
      const rect = containerRef.current.getBoundingClientRect();
      setLogsHeight(prev => Math.max(100, Math.min(prev, rect.height - 100)));
    };

    window.addEventListener('mousemove', onLogsResizeDrag);
    window.addEventListener('mouseup', stopLogsResize);
    window.addEventListener('resize', clampLogsHeightOnWindowResize);

    return () => {
      window.removeEventListener('mousemove', onLogsResizeDrag);
      window.removeEventListener('mouseup', stopLogsResize);
      window.removeEventListener('resize', clampLogsHeightOnWindowResize);
    };
  }, []);

  useEffect(() => {
    localStorage.setItem('verirun-logs-height', String(logsHeight));
    saveToStorage('verirun-design', designCode);
    saveToStorage('verirun-testbench', testbenchCode);
  }, [logsHeight, designCode, testbenchCode]);

  const pollJobStatus = async (id: string) => {
    let consecutiveErrors = 0;
    const maxConsecutiveErrors = 5;

    const pollInterval = setInterval(async () => {
      try {
        const res = await fetch(`/api/v1/jobs/${id}/status`, {
          credentials: 'include'
        });

        if (!res.ok) {
          if (res.status === 404) {
            clearInterval(pollInterval);
            setLogs(prev => [...prev, `Job not found. The job ID may be invalid or expired.`]);
            setIsRunning(false);
            return;
          }
          if (res.status >= 500) {
            consecutiveErrors++;
            if (consecutiveErrors >= maxConsecutiveErrors) {
              clearInterval(pollInterval);
              setLogs(prev => [...prev, `Server error while checking job status. Please check the job later.`]);
              setIsRunning(false);
            }
            return;
          }
          consecutiveErrors++;
          if (consecutiveErrors >= maxConsecutiveErrors) {
            clearInterval(pollInterval);
            setLogs(prev => [...prev, `Failed to fetch job status after multiple attempts.`]);
            setIsRunning(false);
          }
          return;
        }

        consecutiveErrors = 0;
        const job = await res.json();

        if (job.status === 'COMPLETED') {
          clearInterval(pollInterval);
          try {
            const result = JSON.parse(job.result);
            setLogs(prev => [...prev, 'Simulation completed!', ...(result.logs ? [result.logs] : [])]);
            setSimulationPassed(result.passed);
            setJobBuildMode(job.buildMode);
          } catch {
            setLogs(prev => [...prev, 'Simulation completed, but failed to parse results.']);
          }
          setIsRunning(false);
        } else if (job.status === 'FAILED') {
          clearInterval(pollInterval);
          setLogs(prev => [...prev, `Simulation failed: ${job.errorMessage || 'Unknown error'}`]);
          setIsRunning(false);
        }
      } catch {
        consecutiveErrors++;
        if (consecutiveErrors >= maxConsecutiveErrors) {
          clearInterval(pollInterval);
          setLogs(prev => [...prev, 'Lost connection to server while polling. Please check your internet connection.']);
          setIsRunning(false);
        }
      }
    }, 1000);
  };

  const handleRunSimulation = async () => {
    if (!isAuthenticated) return;

    setIsRunning(true);
    setLogs(['Starting simulation...', 'Uploading design and testbench...']);
    setJobId(null);
    setSimulationPassed(false);
    setJobBuildMode(null);

    const buildFlagList = parseFlags(buildFlags);
    const runFlagList = parseFlags(runFlags);
    const wasTraceFst = extractTraceFst(buildFlagList);
    const wasTraceVcd = extractTraceVcd(buildFlagList);
    setJobTraceFst(wasTraceFst);
    setJobTraceVcd(wasTraceVcd);

    const cleanedBuildFlags = buildFlagList.filter(f => f !== '--trace-fst' && f !== '--trace-vcd');

    const options: SimulationOptions = {
      buildMode,
      traceFst: wasTraceFst,
      traceVcd: wasTraceVcd,
      extraFlags: cleanedBuildFlags.length > 0 ? cleanedBuildFlags : undefined,
      simArgs: runFlagList.length > 0 ? runFlagList : undefined,
    };

    try {
      const response = await fetch('/api/v1/simulate', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          designCode,
          testbenchCode,
          options,
        }),
        credentials: 'include'
      });

      if (response.ok) {
        const result = await response.json();
        setJobId(result.jobId);
        setLogs(prev => [...prev, 'Waiting for results...']);
        pollJobStatus(result.jobId);
      } else if (response.status === 400) {
        setLogs(prev => [...prev, 'Invalid input. Please check your Verilog code and try again.']);
        setIsRunning(false);
      } else if (response.status === 401) {
        setLogs(prev => [...prev, 'Session expired. Please log in again.']);
        setIsRunning(false);
      } else if (response.status >= 500) {
        setLogs(prev => [...prev, 'Server error. Please try again later.']);
        setIsRunning(false);
      } else {
        setLogs(prev => [...prev, 'Simulation request failed. Please try again.']);
        setIsRunning(false);
      }
    } catch {
      setLogs(prev => [...prev, 'Cannot connect to server. Check your internet connection.']);
      setIsRunning(false);
    }
  };

  const handleDownloadModel = () => {
    if (!jobId) return;
    window.location.href = `/api/v1/simulate/download/${jobId}`;
  };

  const handleDownloadWaveform = () => {
    if (!jobId) return;
    window.location.href = `/api/v1/simulate/download-waveform/${jobId}`;
  };

  const handleLogout = async () => {
    await logout();
  };

  const handleCopyLogs = async () => {
    try {
      await navigator.clipboard.writeText(logs.join('\n'));
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
    }
  };

  if (!isAuthenticated) {
    return <Navigate to="/login" />;
  }

  return (
    <div className="editor-container" ref={containerRef}>
      <header className="editor-header">
        <h1>VeriRun</h1>
        <button onClick={handleLogout} className="logout-btn">Log out</button>
      </header>

      <div className="editor-layout">
        <div className="editors-row">
          <div className="pane design-pane">
            <div className="pane-header">
              <h3>Design</h3>
            </div>
            <textarea
              className="code-editor"
              value={designCode}
              onChange={(e) => setDesignCode(e.target.value)}
              placeholder={DEFAULT_DESIGN}
            />
          </div>

          <div className="pane testbench-pane">
            <div className="pane-header">
              <h3>Testbench</h3>
            </div>
            <textarea
              className="code-editor"
              value={testbenchCode}
              onChange={(e) => setTestbenchCode(e.target.value)}
              placeholder={DEFAULT_TESTBENCH}
            />
          </div>
        </div>

        <div
          className="resize-divider"
          onMouseDown={startLogsResize}
        />

        <div className="pane logs-pane" style={{ height: logsHeight, flexShrink: 0 }}>
          <div className="pane-header">
            <h3>Simulation log</h3>
            <div className="controls">
              <button
                onClick={handleCopyLogs}
                disabled={logs.length === 0}
                className={`action-btn copy-btn${copied ? ' copied' : ''}`}
              >
                {copied ? 'Copied!' : 'Copy'}
              </button>
              <button
                onClick={handleRunSimulation}
                disabled={isRunning}
                className="action-btn run-btn"
              >
                {isRunning ? 'Running...' : 'Run'}
              </button>
              {jobId && (jobBuildMode === 'BINARY' || jobBuildMode === 'CC_MODEL') && simulationPassed && (
                <button
                  onClick={handleDownloadModel}
                  className="action-btn download-btn"
                >
                  Download model
                </button>
              )}
              {jobId && (jobTraceFst || jobTraceVcd) && simulationPassed && (
                <button
                  onClick={handleDownloadWaveform}
                  className="action-btn waveform-btn"
                >
                  Download waveform
                </button>
              )}
            </div>
          </div>

          <div className="flags-panel">
            <div className="flag-row">
              <label className="flag-label">Build mode</label>
              <select
                value={buildMode}
                onChange={(e) => setBuildMode(e.target.value as BuildMode)}
                disabled={isRunning}
                className="flag-select"
              >
                {BUILD_MODES.map(mode => (
                  <option key={mode.value} value={mode.value}>{mode.label}</option>
                ))}
              </select>
            </div>

            <div className="flag-row">
              <label className="flag-label">Compile options</label>
              <input
                type="text"
                value={buildFlags}
                onChange={(e) => setBuildFlags(e.target.value)}
                disabled={isRunning}
                className="flag-input"
                placeholder="--trace-vcd -Wno-fatal"
              />
            </div>

            <div className="flag-row">
              <label className="flag-label">Run options</label>
              <input
                type="text"
                value={runFlags}
                onChange={(e) => setRunFlags(e.target.value)}
                disabled={isRunning}
                className="flag-input"
                placeholder=""
              />
            </div>
          </div>

          <SimulationLogs logs={logs} />
        </div>
      </div>
    </div>
  );
};

export default Editor;
