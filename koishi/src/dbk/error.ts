import { ErrorCode } from "../gen/dbk/v1/common_pb";

export class DbkRpcError extends Error {
  readonly code: ErrorCode;

  constructor(code: ErrorCode, detail: string) {
    super(detail);
    this.name = "DbkRpcError";
    this.code = code;
  }
}
