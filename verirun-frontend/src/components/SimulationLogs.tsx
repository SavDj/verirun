import React, { useEffect, useRef } from 'react';

interface SimulationLogsProps {
  logs: string[];
  isRunning: boolean;
}

const SimulationLogs: React.FC<SimulationLogsProps> = ({ logs }) => {
  const logsEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    scrollToBottom();
  }, [logs]);

  const scrollToBottom = () => {
    logsEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  };

  return (
    <div className="simulation-logs-container">
      {logs.length === 0 ? (
        <div className="no-logs">Click "Run simulation" to start.</div>
      ) : (
        <pre className="logs-content">
          {logs.join('\n')}
        </pre>
      )}
      <div ref={logsEndRef} />
    </div>
  );
};

export default SimulationLogs;