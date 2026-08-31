import { create, fromBinary, fromJsonString, toBinary, toJsonString, type DescMessage, type MessageShape } from "@bufbuild/protobuf";
import { ErrorCode } from "../gen/dbk/v1/common_pb";
import { FrameSchema, type Frame } from "../gen/dbk/v1/frame_pb";
import { DbkRpcError } from "./error";
import type { DbkEncoding } from "./protocol";

export function encodeFrame(frame: Frame, encoding: DbkEncoding): Uint8Array | string {
  if (encoding === "json") {
    return toJsonString(FrameSchema, frame);
  }
  return toBinary(FrameSchema, frame);
}

export function decodeFrame(data: Uint8Array | string, encoding: DbkEncoding): Frame {
  if (encoding === "json") {
    const text = typeof data === "string" ? data : new TextDecoder().decode(data);
    return fromJsonString(FrameSchema, text);
  }
  if (typeof data === "string") {
    throw new DbkRpcError(ErrorCode.PROTOCOL, "DBK only accepts protobuf binary frames");
  }
  return fromBinary(FrameSchema, data);
}

export function encodePayload<Desc extends DescMessage>(schema: Desc, message: MessageShape<Desc>): Uint8Array {
  return toBinary(schema, message);
}

export function decodePayload<Desc extends DescMessage>(schema: Desc, bytes: Uint8Array): MessageShape<Desc> {
  return fromBinary(schema, bytes);
}

export function createFrame(init: Parameters<typeof create<typeof FrameSchema>>[1]): Frame {
  return create(FrameSchema, init);
}

export function toBytes(data: unknown): Uint8Array {
  if (data instanceof Uint8Array) return data;
  if (data instanceof ArrayBuffer) return new Uint8Array(data);
  if (ArrayBuffer.isView(data)) {
    return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  }
  if (Array.isArray(data)) {
    const chunks = data.map((part) => toBytes(part));
    const total = chunks.reduce((sum, chunk) => sum + chunk.byteLength, 0);
    const out = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      out.set(chunk, offset);
      offset += chunk.byteLength;
    }
    return out;
  }
  throw new DbkRpcError(ErrorCode.PROTOCOL, "DBK frame is not binary");
}
