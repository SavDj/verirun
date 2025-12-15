import React, { useState } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { Navigate } from 'react-router-dom';
import SimulationLogs from './SimulationLogs';

const Editor: React.FC = () => {
  const { isAuthenticated, logout } = useAuth();
  const [designCode, setDesignCode] = useState<string>('// Enter your Verilog design here\nmodule top();\n  \nendmodule');
  const [testbenchCode, setTestbenchCode] = useState<string>('// Enter your testbench here\nmodule tb();\n  \nendmodule');
  const [logs, setLogs] = useState<string[]>([]);
  const [isRunning, setIsRunning] = useState<boolean>(false);
  const [generateModelOnly, setGenerateModelOnly] = useState<boolean>(false);
  const [jobId, setJobId] = useState<string | null>(null);
  const [simulationPassed, setSimulationPassed] = useState<boolean>(false);

  const handleRunSimulation = async () => {
    if (!isAuthenticated) return;

    setIsRunning(true);
    setLogs(['Starting simulation...', 'Uploading design and testbench...']);
    setJobId(null);
    setSimulationPassed(false); // Reset simulation passed status

    try {
      const response = await fetch('/api/v1/simulate', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          designCode: designCode,
          testbenchCode: testbenchCode,
          generateModelOnly: generateModelOnly
        }),
        credentials: 'include'
      });

      if (response.ok) {
        const result = await response.json();
        setLogs(prev => [...prev, ...(Array.isArray(result.logs) ? result.logs : [result.logs])]);
        setJobId(result.jobId);
        if (generateModelOnly && result.passed !== undefined) {
          setSimulationPassed(result.passed);
        }
      } else {
        const errorText = await response.text();
        setLogs(prev => [...prev, `Error: ${errorText}`]);
      }
    } catch (error) {
      console.error('Simulation error:', error);
      const errorMessage = error instanceof Error ? error.message : 'An unknown error occurred';
      setLogs(prev => [...prev, `Connection error: ${errorMessage}`]);
    } finally {
      setIsRunning(false);
    }
  };

  const handleDownloadModel = () => {
    if (!jobId) return;

    const downloadUrl = `/api/v1/simulate/download/${jobId}`;
    const link = document.createElement('a');
    link.href = downloadUrl;
    link.download = `verilator_model_${jobId}.zip`;
    link.target = '_blank';
    link.click();
  };

  const handleLogout = async () => {
    await logout();
  };

  if (!isAuthenticated) {
    return <Navigate to="/login" />;
  }

  return (
    <div className="editor-container">
      <header className="editor-header">
        <h1>VeriRun</h1>
        <button onClick={handleLogout} className="logout-btn">Logout</button>
      </header>

      <div className="editor-layout">
        <div className="pane design-pane">
          <div className="pane-header">
            <h3>Design (RTL)</h3>
          </div>
          <textarea
            className="code-editor"
            value={designCode}
            onChange={(e) => setDesignCode(e.target.value)}
            placeholder="Enter your Verilog design here..."
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
            placeholder="Enter your testbench here..."
          />
        </div>

        <div className="pane logs-pane">
          <div className="pane-header">
            <h3>Simulation logs</h3>
            <div className="controls">
              <label className="toggle-label">
                <input
                  type="checkbox"
                  checked={generateModelOnly}
                  onChange={(e) => setGenerateModelOnly(e.target.checked)}
                  disabled={isRunning}
                />
                Generate model only
              </label>
              <button
                onClick={handleRunSimulation}
                disabled={isRunning}
                className="run-btn"
              >
                {isRunning ? 'Running...' : 'Run simulation'}
              </button>
              {jobId && generateModelOnly && simulationPassed && (
                <button
                  onClick={handleDownloadModel}
                  className="download-btn"
                >
                  Download model
                </button>
              )}
            </div>
          </div>
          <SimulationLogs logs={logs} isRunning={isRunning} />
        </div>
      </div>
    </div>
  );
};

export default Editor;