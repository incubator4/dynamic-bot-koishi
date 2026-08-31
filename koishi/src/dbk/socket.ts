import { decodeFrame, toBytes } from "./codec";
import type { DbkEncoding } from "./protocol";
import type { DbkGatewaySession } from "./session";

export interface DbkSocket {
  readyState: number;
  binaryType?: string;
  send(data: Uint8Array | string): void;
  close(code?: number, reason?: string): void;
  on?(event: string, listener: (...args: unknown[]) => void): void;
  addEventListener?(type: string, listener: (event: { data?: unknown; code?: number; reason?: string }) => void): void;
}

export const WS_OPEN = 1;

export function sendSocket(socket: DbkSocket, data: Uint8Array | string): void {
  if (socket.readyState !== WS_OPEN) {
    throw new Error("WebSocket is not open");
  }
  socket.send(data);
}

export function attachSocket(socket: DbkSocket, session: DbkGatewaySession, encoding: DbkEncoding): void {
  if (socket.binaryType !== undefined) {
    socket.binaryType = "arraybuffer";
  }

  const onMessage = (data: unknown, isBinary?: boolean) => {
    const binary = isBinary ?? typeof data !== "string";
    if (encoding === "binary" && !binary) {
      session.rejectText();
      return;
    }
    try {
      const raw = typeof data === "string" ? data : toBytes(data);
      session.onFrame(decodeFrame(raw, encoding));
    } catch (error) {
      session.rejectDecode(error);
    }
  };

  const onClose = () => session.close("ws close");

  if (typeof socket.on === "function") {
    socket.on("message", (data, isBinary) => onMessage(data, isBinary as boolean | undefined));
    socket.on("close", onClose);
    return;
  }

  socket.addEventListener?.("message", (event) => onMessage(event.data));
  socket.addEventListener?.("close", onClose);
}
