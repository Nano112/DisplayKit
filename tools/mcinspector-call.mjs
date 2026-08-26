#!/usr/bin/env node

import fs from "node:fs";
import net from "node:net";
import path from "node:path";

const [toolName, rawArguments = "{}", imagePath] = process.argv.slice(2);
const usage = "usage: mcinspector-call.mjs <tool|--list> [json-arguments] [image-output]";
if (!toolName || toolName === "--help" || toolName === "-h") {
  console[toolName ? "log" : "error"](usage);
  process.exit(toolName ? 0 : 2);
}

let toolArguments;
try {
  toolArguments = JSON.parse(rawArguments);
} catch (error) {
  console.error(`invalid JSON arguments: ${error.message}`);
  process.exit(2);
}

const isList = toolName === "--list";
const payload = JSON.stringify({
  jsonrpc: "2.0",
  id: Date.now(),
  method: isList ? "tools/list" : "tools/call",
  params: isList ? {} : { name: toolName, arguments: toolArguments },
});

const request = Buffer.from([
  "POST /mcp HTTP/1.1",
  "Host: 127.0.0.1:38271",
  "Content-Type: application/json",
  "Accept: application/json, text/event-stream",
  `Content-Length: ${Buffer.byteLength(payload)}`,
  "Connection: close",
  "",
  payload,
].join("\r\n"));

function decodeChunked(buffer) {
  const chunks = [];
  let cursor = 0;
  while (cursor < buffer.length) {
    const end = buffer.indexOf("\r\n", cursor);
    if (end < 0) throw new Error("incomplete chunk size");
    const size = Number.parseInt(buffer.subarray(cursor, end).toString("ascii"), 16);
    if (!Number.isFinite(size)) throw new Error("invalid chunk size");
    if (size === 0) break;
    cursor = end + 2;
    chunks.push(buffer.subarray(cursor, cursor + size));
    cursor += size + 2;
  }
  return Buffer.concat(chunks);
}

function extractJson(headers, body) {
  const transferEncoding = headers.get("transfer-encoding")?.toLowerCase();
  const decoded = transferEncoding?.includes("chunked") ? decodeChunked(body) : body;
  const text = decoded.toString("utf8").trim();
  if (headers.get("content-type")?.includes("text/event-stream")) {
    const events = text
      .split(/\r?\n/)
      .filter((line) => line.startsWith("data:"))
      .map((line) => line.slice(5).trim())
      .filter(Boolean);
    if (events.length === 0) throw new Error("empty event-stream response");
    return JSON.parse(events.at(-1));
  }
  return JSON.parse(text);
}

function compact(value) {
  if (typeof value !== "string") return value;
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}

function report(response) {
  if (response.error) {
    console.error(JSON.stringify(response.error, null, 2));
    process.exitCode = 1;
    return;
  }

  const result = response.result ?? {};
  if (Array.isArray(result.tools)) {
    console.log(JSON.stringify(result.tools, null, 2));
    return;
  }
  const output = [];
  for (const block of result.content ?? []) {
    if (block.type === "image") {
      if (!imagePath) {
        output.push({ type: "image", mimeType: block.mimeType, bytes: Buffer.byteLength(block.data, "base64") });
        continue;
      }
      const resolved = path.resolve(imagePath);
      fs.writeFileSync(resolved, Buffer.from(block.data, "base64"));
      output.push({ type: "image", mimeType: block.mimeType, bytes: fs.statSync(resolved).size, path: resolved });
    } else if (block.type === "text") {
      output.push(compact(block.text));
    } else {
      output.push(block);
    }
  }
  if (result.isError) process.exitCode = 1;
  console.log(JSON.stringify(output.length === 1 ? output[0] : output, null, 2));
}

const socket = net.createConnection({ host: "127.0.0.1", port: 38271 });
const received = [];
let expectedLength = null;
let headerLength = null;
let chunked = false;

const configuredTimeout = Number(process.env.MCINSPECTOR_TIMEOUT_MS);
const waitTicks = toolName === "wait_ticks" ? Number(toolArguments.ticks ?? 1) : 0;
const inferredTimeout = waitTicks > 0 ? waitTicks * 50 + 35_000 : 30_000;
socket.setTimeout(Number.isFinite(configuredTimeout) && configuredTimeout > 0 ? configuredTimeout : inferredTimeout);
socket.on("connect", () => socket.write(request));
socket.on("data", (chunk) => {
  received.push(chunk);
  const buffer = Buffer.concat(received);
  if (headerLength === null) {
    const boundary = buffer.indexOf("\r\n\r\n");
    if (boundary < 0) return;
    headerLength = boundary + 4;
    const rawHeaders = buffer.subarray(0, boundary).toString("ascii").split("\r\n");
    const headers = new Map(rawHeaders.slice(1).map((line) => {
      const separator = line.indexOf(":");
      return [line.slice(0, separator).trim().toLowerCase(), line.slice(separator + 1).trim()];
    }));
    const contentLength = headers.get("content-length");
    expectedLength = contentLength === undefined ? null : Number(contentLength);
    chunked = headers.get("transfer-encoding")?.toLowerCase().includes("chunked") === true;
  }
  const completeLength = expectedLength !== null && buffer.length >= headerLength + expectedLength;
  const completeChunks = chunked && buffer.subarray(headerLength).includes(Buffer.from("\r\n0\r\n\r\n"));
  if (completeLength || completeChunks) socket.destroy();
});
socket.on("timeout", () => socket.destroy(new Error("MC-Inspector request timed out")));
socket.on("error", (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
socket.on("close", () => {
  if (received.length === 0) {
    if (!process.exitCode) {
      console.error("MC-Inspector closed the connection without a response");
      process.exitCode = 1;
    }
    return;
  }
  try {
    const response = Buffer.concat(received);
    const boundary = response.indexOf("\r\n\r\n");
    if (boundary < 0) throw new Error("invalid HTTP response");
    const headerLines = response.subarray(0, boundary).toString("ascii").split("\r\n");
    const status = Number(headerLines[0].split(" ")[1]);
    if (status < 200 || status >= 300) throw new Error(`MC-Inspector returned HTTP ${status}`);
    const headers = new Map(headerLines.slice(1).map((line) => {
      const separator = line.indexOf(":");
      return [line.slice(0, separator).trim().toLowerCase(), line.slice(separator + 1).trim()];
    }));
    report(extractJson(headers, response.subarray(boundary + 4)));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
});
