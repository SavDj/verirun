export type BuildMode = 'LINT_ONLY' | 'CC_MODEL' | 'BINARY';

export type OptLevel = 'O0' | 'O1' | 'O2' | 'O3';

export interface SimulationOptions {
  buildMode?: BuildMode;
  traceFst?: boolean;
  traceVcd?: boolean;
  traceDepth?: number;
  traceStruct?: boolean;
  coverage?: boolean;
  parallelJobs?: number;
  optLevel?: OptLevel;
  includes?: string[];
  defines?: string[];
  warningsOff?: string[];
  extraFlags?: string[];
  simArgs?: string[];
}

export interface SimulateRequest {
  designCode: string;
  testbenchCode: string;
  options?: SimulationOptions;
}

export interface JobStatusResponse {
  jobId: string;
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED';
  buildMode: BuildMode;
  result?: string;
  errorMessage?: string;
}

export interface SimulateResponse {
  jobId: string;
}
