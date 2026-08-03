#!/usr/bin/env node
/**
 * WebCode Local Agent
 * 本地 AI 编程助手 Agent —— 零依赖，API 与 WebCode 云端版完全兼容。
 * 运行环境：Android 应用内嵌的 Termux 运行时 / 任意 Linux / macOS
 *
 * 兼容端点（与 WebCode 一致）:
 *   POST /api/chat             {sessionId?, content} -> SSE 事件流
 *   GET  /api/chat?sessionId=  SSE 重连（订阅运行中的 Agent）
 *   POST /api/chat/abort       {sessionId}
 *   POST /api/approve          {requestId, approved}
 *   POST /api/answer           {questionId, answer}
 *   GET  /api/sessions | POST /api/sessions
 *   GET/PATCH/DELETE /api/sessions/:id
 *   GET  /api/usage   GET /api/workspace
 *   GET/PUT /api/settings   GET /api/auth/me   POST /api/auth/login
 */
"use strict";

const http = require("http");
const https = require("https");
const fs = require("fs");
const path = require("path");
const os = require("os");
const crypto = require("crypto");
const { execFile, spawn } = require("child_process");

/* ============ 配置 ============ */
const HOME_DIR = process.env.WEBCODE_HOME || process.env.HOME || os.homedir();
const DATA_DIR = path.join(HOME_DIR, ".webcode-local");
const CONFIG_FILE = path.join(DATA_DIR, "config.json");
const SESSIONS_FILE = path.join(DATA_DIR, "sessions.json");
const USAGE_FILE = path.join(DATA_DIR, "usage.json");

const PORT = parseInt(process.env.PORT || "3456", 10);
const HOST = process.env.HOST || "127.0.0.1";
const MOCK = process.env.MOCK === "1";
const TOKEN = process.env.WEBCODE_TOKEN || "local";

const MAX_STEPS = 16;
const MAX_TEXT_OUTPUT = 30000;

fs.mkdirSync(DATA_DIR, { recursive: true });

function loadConfig() {
  try {
    return JSON.parse(fs.readFileSync(CONFIG_FILE, "utf8"));
  } catch {
    return {
      baseUrl: "https://api.deepseek.com/v1",
      model: "deepseek-chat",
      apiKey: "",
      workspace: HOME_DIR,
      thinking: true,
      maxSteps: 16,
    };
  }
}

const config = loadConfig();
const WORKSPACE = fs.realpathSync(config.workspace || HOME_DIR);
process.chdir(WORKSPACE);

/* ============ 危险命令检测（移植自 WebCode safety.ts） ============ */
const DANGEROUS_PATTERNS = [
  /\brm\b\s+(-rf\b|-[a-z]*r[a-z]*\s+-[a-z]*f[a-z]*\b|-[a-z]*f[a-z]*\s+-[a-z]*r[a-z]*\b)/i,
  /\bmkfs/i,
  /\bshutdown\b|\breboot\b|\bpoweroff\b|\binit\s+0\b/i,
  /\bdd\s+if=.*of=\/dev\/(s|v)d/i,
  /\bsudo\b/i,
  /\bchmod\s+(-R\s+)?777\b/i,
  /\bgit\s+push\s+.*--force\b/i,
  /\bgit\s+reset\s+--hard\b/i,
  /\bcurl\b[^|;]*\|\s*(ba)?sh\b/i,
  /\bwget\b[^|;]*\|\s*(ba)?sh\b/i,
  /\bkill\s+(-9\s+)?-?1\b/i,
  /\bfind\s+\/.*-delete\b/i,
];

function classifyCommand(cmd) {
  return DANGEROUS_PATTERNS.some((re) => re.test(cmd)) ? "dangerous" : "safe";
}
function needsApproval(cmd) {
  return classifyCommand(cmd) === "dangerous";
}

/* ============ 会话存储 ============ */
let sessions = [];
try {
  sessions = JSON.parse(fs.readFileSync(SESSIONS_FILE, "utf8"));
} catch {}

let usage = { promptTokens: 0, completionTokens: 0, requestCount: 0 };
try {
  usage = JSON.parse(fs.readFileSync(USAGE_FILE, "utf8"));
} catch {}

function persistSessions() {
  fs.writeFileSync(SESSIONS_FILE, JSON.stringify(sessions, null, 1));
}
function persistUsage() {
  fs.writeFileSync(USAGE_FILE, JSON.stringify(usage, null, 1));
}

const id = (prefix) =>
  `${prefix}_${crypto.randomUUID().slice(0, 13)}`;

function findSession(sessionId) {
  return sessions.find((s) => s.id === sessionId);
}

/* ============ 运行中的 Agent / 审批 / 提问 ============ */
const runningAgents = new Map(); // sessionId -> {controller, listeners:Set}
const approvals = new Map(); // requestId -> {resolve}
const questions = new Map(); // questionId -> {resolve}

function emitter(sessionId) {
  if (!runningAgents.has(sessionId)) {
    runningAgents.set(sessionId, { controller: null, listeners: new Set() });
  }
  return runningAgents.get(sessionId);
}

function emit(sessionId, type, data = {}) {
  const e = emitter(sessionId);
  const event = { type, ...data };
  for (const fn of e.listeners) {
    try {
      fn(event);
    } catch {}
  }
}

function isRunning(sessionId) {
  const e = runningAgents.get(sessionId);
  return e && e.controller && !e.controller.signal.aborted;
}

/* ============ SSE 输出 ============ */
function sse(event, data) {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
}

/* ============ LLM 客户端（OpenAI 兼容，streaming + 工具调用） ============ */
function mockLlm({ messages, signal }) {
  // MOCK 模式：脚本化回复，演示工具循环、审批、提问全流程
  return new Promise((resolve) => {
    const lastUser = [...messages].reverse().find((m) => m.role === "user");
    const content = lastUser?.content || "";
    // 若上一轮工具已被回答/执行，则本轮输出正文结束循环
    const lastToolMsg = [...messages].reverse().find(
      (m) => m.role === "assistant" && Array.isArray(m.tool_calls) && m.tool_calls.length
    );
    const toolResult = lastToolMsg?.tool_calls?.length
      ? messages.filter((m) => m.role === "tool").at(-1)?.content || ""
      : "";
    const answered =
      toolResult.startsWith("用户回答") ||
      toolResult.startsWith("用户拒绝") ||
      toolResult.startsWith("已执行");

    let toolCalls = [];
    let text = "";

    if (answered) {
      text = `（MOCK）工具已完成，任务结束。`;
    } else if (content.includes("问题")) {
      toolCalls = [
        {
          id: "mock_ask",
          name: "ask_user",
          arguments: JSON.stringify({
            question: "请选择测试选项（MOCK 演示）",
            options: ["选项A", "选项B"],
            multiple: false,
          }),
        },
      ];
    } else if (content.includes("命令") || content.includes("删除")) {
      toolCalls = [
        {
          id: "mock_cmd",
          name: "run_command",
          arguments: JSON.stringify({ command: "rm -rf /tmp/opencode/mocktest" }),
        },
      ];
    } else if (content.includes("文件")) {
      toolCalls = [
        {
          id: "mock_read",
          name: "read_file",
          arguments: JSON.stringify({ path: "agent.js", limit: 3 }),
        },
      ];
    } else {
      text = `（MOCK 回复）已收到你的消息。\n\n**你好**，这是本地 Agent 的离线演示模式。\n\`代码片段\` 和 **加粗** 都能渲染。`;
    }
    setTimeout(() => {
      resolve({ text, reasoning: "（MOCK 推理过程）正在分析任务…", toolCalls, finishReason: "stop", usage: null });
    }, 300);
  });
}

function llmChat({ messages, tools, signal, maxTokens }) {
  if (MOCK) return mockLlm({ messages, signal });
  return new Promise((resolve, reject) => {
    const url = new URL(`${config.baseUrl.replace(/\/+$/, "")}/chat/completions`);
    const body = JSON.stringify({
      model: config.model,
      messages,
      tools: tools || undefined,
      tool_choice: "auto",
      stream: true,
      max_tokens: maxTokens || 4096,
      ...(config.thinking
        ? {}
        : { extra_body: { thinking: { type: "disabled" } } }),
    });

    const headers = {
      "Content-Type": "application/json",
      Authorization: `Bearer ${config.apiKey}`,
    };
    if (config.apiKeyHeader) headers[config.apiKeyHeader] = config.apiKey;

    const mod = url.protocol === "https:" ? https : http;
    const req = mod.request(url, { method: "POST", headers }, (res) => {
      if (res.statusCode !== 200) {
        let buf = "";
        res.on("data", (c) => (buf += c));
        res.on("end", () =>
          reject(new Error(`LLM 请求失败 (${res.statusCode}): ${buf.slice(0, 400)}`))
        );
        return;
      }

      let buffer = "";
      let textChunks = "";
      let thinkingChunks = "";
      let toolCalls = {}; // index -> {id, name, arguments}
      let finishReason = null;
      let usage = null;

      res.on("data", (chunk) => {
        buffer += chunk.toString("utf8");
        let idx;
        while ((idx = buffer.indexOf("\n")) !== -1) {
          const line = buffer.slice(0, idx).trim();
          buffer = buffer.slice(idx + 1);
          if (!line.startsWith("data:")) continue;
          const data = line.slice(5).trim();
          if (data === "[DONE]") {
            onDone();
            continue;
          }
          try {
            const json = JSON.parse(data);
            const delta = json.choices?.[0]?.delta || {};
            if (delta.reasoning_content) {
              thinkingChunks += delta.reasoning_content;
            }
            if (delta.content) {
              textChunks += delta.content;
            }
            if (delta.tool_calls) {
              for (const tc of delta.tool_calls) {
                const i = tc.index ?? 0;
                toolCalls[i] = toolCalls[i] || { id: "", name: "", arguments: "" };
                if (tc.id) toolCalls[i].id = tc.id;
                if (tc.function?.name) toolCalls[i].name += tc.function.name;
                if (tc.function?.arguments) toolCalls[i].arguments += tc.function.arguments;
              }
            }
            if (json.choices?.[0]?.finish_reason) {
              finishReason = json.choices[0].finish_reason;
            }
            if (json.usage) usage = json.usage;
          } catch {}
        }
      });

      let settled = false;
      const onDone = () => {
        if (settled) return;
        settled = true;
        resolve({
          text: textChunks,
          reasoning: thinkingChunks,
          toolCalls: Object.values(toolCalls).map((tc) => ({
            id: tc.id || `call_${crypto.randomUUID().slice(0, 8)}`,
            name: tc.name,
            arguments: tc.arguments,
          })),
          finishReason,
          usage,
        });
      };
      res.on("end", onDone);
      res.on("error", (e) => !settled && reject(e));
    });
    req.on("error", reject);
    if (signal) {
      signal.addEventListener("abort", () => {
        req.destroy();
        reject(new Error("aborted"));
      });
    }
    req.end(body);
  });
}

/* ============ 消息构建 ============ */
function buildMessages(session) {
  const messages = [];
  const tools = Object.values(TOOL_DEFS).map((t) => ({
    type: "function",
    function: { name: t.name, description: t.desc, parameters: t.params },
  }));

  let pendingToolCalls = [];

  for (const msg of session.messages) {
    if (msg.role === "user") {
      const text = msg.parts
        .filter((p) => p.type === "text")
        .map((p) => p.text)
        .join("");
      messages.push({ role: "user", content: text });
      continue;
    }
    // assistant
    const text = msg.parts
      .filter((p) => p.type === "text")
      .map((p) => p.text)
      .join("");
    const toolParts = msg.parts.filter((p) => p.type === "tool");

    if (toolParts.length) {
      messages.push({
        role: "assistant",
        content: text || null,
        tool_calls: toolParts.map((p, i) => ({
          id: p.id,
          type: "function",
          function: {
            name: p.tool,
            arguments:
              typeof p.input === "string"
                ? p.input
                : JSON.stringify(p.input ?? {}),
          },
        })),
      });
      for (const p of toolParts) {
        const output =
          p.state === "error"
            ? `错误: ${p.output}`
            : p.output || "(无输出)";
        messages.push({ role: "tool", tool_call_id: p.id, content: output });
      }
    } else {
      messages.push({ role: "assistant", content: text || null });
    }
  }

  if (pendingToolCalls.length) {
    messages.push({ role: "assistant", content: null, tool_calls: pendingToolCalls });
  }
  void pendingToolCalls;
  return { messages, tools };
}

/* ============ 工具 ============ */
function resolvePath(p) {
  const abs = path.resolve(WORKSPACE, p);
  const rel = path.relative(WORKSPACE, abs);
  if (rel.startsWith("..") || path.isAbsolute(rel)) {
    throw new Error(`路径越界（仅允许工作区内）: ${p}`);
  }
  return abs;
}

async function runShell(command, { timeout = 120000, cwd = WORKSPACE } = {}) {
  return new Promise((resolve) => {
    const shell = process.env.SHELL || "/bin/bash";
    const child = spawn(shell, ["-c", command], {
      cwd,
      env: process.env,
      timeout,
    });
    let out = "";
    let err = "";
    child.stdout.on("data", (d) => (out += d));
    child.stderr.on("data", (d) => (err += d));
    child.on("close", (code) => {
      const o = (out || err).trim();
      resolve(o ? o : `(退出码 ${code})`);
    });
    child.on("error", (e) => resolve(`执行失败: ${e.message}`));
  });
}

const TOOL_DEFS = {
  read_file: {
    name: "read_file",
    desc: "读取文件内容，可限制行数",
    params: {
      type: "object",
      properties: {
        path: { type: "string", description: "工作区内的相对或绝对路径" },
        limit: { type: "integer", description: "最多读取的行数" },
      },
      required: ["path"],
    },
    async run(args) {
      const p = resolvePath(args.path);
      const stat = fs.statSync(p);
      if (!stat.isFile()) return `不是文件: ${p}`;
      let text = fs.readFileSync(p, "utf8");
      const lines = text.split("\n");
      const limit = args.limit ? parseInt(args.limit, 10) : 0;
      const shown = limit > 0 ? lines.slice(0, limit) : lines;
      const total = lines.length;
      const msg = `文件 ${path.basename(p)} (${total} 行，${stat.size} 字节)`;
      return `${msg}，显示第 1-${shown.length} 行:\n${shown.join("\n")}`;
    },
  },
  write_file: {
    name: "write_file",
    desc: "写入文件（覆盖）",
    params: {
      type: "object",
      properties: {
        path: { type: "string" },
        content: { type: "string" },
      },
      required: ["path", "content"],
    },
    async run(args) {
      const p = resolvePath(args.path);
      fs.mkdirSync(path.dirname(p), { recursive: true });
      fs.writeFileSync(p, String(args.content ?? ""));
      return `已写入 ${p} (${String(args.content ?? "").length} 字符)`;
    },
  },
  list_files: {
    name: "list_files",
    desc: "列出目录内容",
    params: {
      type: "object",
      properties: {
        path: { type: "string" },
      },
      required: ["path"],
    },
    async run(args) {
      const p = resolvePath(args.path || ".");
      const entries = fs.readdirSync(p, { withFileTypes: true });
      return entries
        .map((e) => {
          let extra = "";
          try {
            const st = fs.statSync(path.join(p, e.name));
            extra = e.isDirectory() ? "/" : ` (${st.size} B)`;
          } catch {}
          return `${e.isDirectory() ? "📁" : "📄"} ${e.name}${extra}`;
        })
        .join("\n");
    },
  },
  run_command: {
    name: "run_command",
    desc: "在设备上执行 shell 命令（bash）。危险命令需要用户审批",
    params: {
      type: "object",
      properties: {
        command: { type: "string", description: "要执行的 bash 命令" },
        timeout: { type: "integer", description: "超时秒数，默认 120" },
      },
      required: ["command"],
    },
    async run(args, ctx) {
      const command = String(args.command);
      const dangerous = needsApproval(command);
      if (dangerous) {
        const requestId = `req_${crypto.randomUUID().slice(0, 8)}`;
        const ok = await ctx.requestApproval({ requestId, command, danger: "dangerous" });
        if (!ok) return "用户拒绝执行该操作";
      }
      return runShell(command, { timeout: (args.timeout || 120) * 1000 });
    },
  },
  ask_user: {
    name: "ask_user",
    desc: "向用户提问，等待回答",
    params: {
      type: "object",
      properties: {
        question: { type: "string" },
        options: { type: "array", items: { type: "string" }, maxItems: 6 },
        multiple: { type: "boolean" },
      },
      required: ["question"],
    },
    async run(args, ctx) {
      const questionId = `q_${crypto.randomUUID().slice(0, 8)}`;
      const answer = await ctx.askUser({
        questionId,
        question: args.question,
        options: args.options,
        multiple: args.multiple,
      });
      return `用户回答：${answer}`;
    },
  },
  workspace_info: {
    name: "workspace_info",
    desc: "查看当前工作目录信息（设备信息）",
    params: { type: "object", properties: {} },
    async run() {
      const info = [
        `工作目录: ${WORKSPACE}`,
        `设备: ${os.hostname()}`,
        `系统: ${os.type()} ${os.release()}`,
        `内存: ${Math.round(os.totalmem() / 1048576)} MB`,
        `CPU: ${os.cpus()[0]?.model || "?"}`,
      ];
      return info.join("\n");
    },
  },
};

/* ============ Agent 主循环 ============ */
async function runAgent(sessionId, assistantMessageId) {
  const e = emitter(sessionId);
  const controller = new AbortController();
  e.controller = controller;
  const signal = controller.signal;

  try {
    let silentRounds = 0;
    for (let step = 1; step <= (config.maxSteps || MAX_STEPS); step++) {
      if (signal.aborted) {
        emit(sessionId, "aborted", {});
        return;
      }
      const status = step === 1 ? "思考中…" : `第 ${step} 轮工具循环…`;
      emit(sessionId, "status", { messageId: assistantMessageId, status });

      const session = findSession(sessionId);
      if (!session) return;
      const { messages, tools } = buildMessages(session);

      let result;
      try {
        result = await llmChat({ messages, tools, signal });
      } catch (err) {
        if (signal.aborted) {
          emit(sessionId, "aborted", {});
          return;
        }
        throw err;
      }

      if (result.usage) {
        usage.promptTokens += result.usage.prompt_tokens || 0;
        usage.completionTokens += result.usage.completion_tokens || 0;
        usage.requestCount += 1;
        persistUsage();
      }

      // 推理内容
      if (result.reasoning) {
        emit(sessionId, "reasoning_delta", {
          messageId: assistantMessageId,
          text: result.reasoning,
        });
      }
      // 正文
      if (result.text) {
        emit(sessionId, "delta", { messageId: assistantMessageId, text: result.text });
      }

      if (!result.toolCalls.length) {
        if (!result.text && result.reasoning) {
          silentRounds++;
          if (silentRounds >= 3) break;
          continue;
        }
        break;
      }
      silentRounds = 0;

      // 追加消息 parts 并持久化
      const sess = findSession(sessionId);
      const am = sess?.messages.find((m) => m.id === assistantMessageId);
      if (am) {
        if (result.reasoning) am.parts.push({ type: "thinking", text: result.reasoning });
        if (result.text) am.parts.push({ type: "text", text: result.text });
      }

      for (const tc of result.toolCalls) {
        if (signal.aborted) break;
        const def = TOOL_DEFS[tc.name];
        if (!def) {
          emit(sessionId, "tool_error", {
            messageId: assistantMessageId,
            partId: tc.id,
            error: `未知工具: ${tc.name}`,
          });
          continue;
        }
        let input = {};
        try {
          input = tc.arguments ? JSON.parse(tc.arguments) : {};
        } catch {
          input = { raw: tc.arguments };
        }
        const title = tc.arguments
          ? `${tc.name} ${tc.arguments.slice(0, 60)}`
          : tc.name;
        const part = {
          type: "tool",
          id: tc.id,
          tool: tc.name,
          title,
          state: "running",
          input,
        };
        const am2 = findSession(sessionId)?.messages.find(
          (m) => m.id === assistantMessageId
        );
        if (am2) am2.parts.push(part);
        persistSessions();
        emit(sessionId, "tool_start", {
          messageId: assistantMessageId,
          partId: tc.id,
          tool: tc.name,
          title,
          input,
        });

        const ctx = {
          requestApproval: async (req) => {
            part.state = "requires_action";
            part.approval = req;
            emit(sessionId, "approval_required", {
              messageId: assistantMessageId,
              partId: tc.id,
              approval: req,
            });
            return await new Promise((resolve) => {
              approvals.set(req.requestId, { resolve });
              setTimeout(() => {
                if (approvals.has(req.requestId)) {
                  approvals.delete(req.requestId);
                  resolve(false);
                }
              }, 30 * 60 * 1000);
            });
          },
          askUser: async (q) => {
            part.state = "requires_action";
            part.question = q;
            emit(sessionId, "question_required", {
              messageId: assistantMessageId,
              partId: tc.id,
              question: q,
            });
            return await new Promise((resolve) => {
              questions.set(q.questionId, { resolve });
              setTimeout(() => {
                if (questions.has(q.questionId)) {
                  questions.delete(q.questionId);
                  resolve("（用户未回答）");
                }
              }, 30 * 60 * 1000);
            });
          },
        };

        try {
          const output = await def.run(input, ctx);
          part.state = "completed";
          part.output = String(output);
          emit(sessionId, "tool_output", {
            messageId: assistantMessageId,
            partId: tc.id,
            output: String(output),
            truncated: String(output).length >= MAX_TEXT_OUTPUT,
          });
        } catch (err) {
          part.state = "error";
          part.output = err.message || String(err);
          emit(sessionId, "tool_error", {
            messageId: assistantMessageId,
            partId: tc.id,
            error: part.output,
          });
        }
        if (signal.aborted) break;
      }
      persistSessions();
    }
    emit(sessionId, "done", {
      messageId: assistantMessageId,
      finishReason: "stop",
      sessionId,
    });
  } catch (err) {
    emit(sessionId, "error", { message: String(err) });
  } finally {
    const sess = findSession(sessionId);
    if (sess) sess.updatedAt = Date.now();
    persistSessions();
    e.controller = null;
    e.listeners.clear();
  }
}

/* ============ HTTP 服务器 ============ */
function readBody(req) {
  return new Promise((resolve) => {
    let buf = "";
    req.on("data", (c) => (buf += c));
    req.on("end", () => {
      try {
        resolve(buf ? JSON.parse(buf) : {});
      } catch {
        resolve({});
      }
    });
  });
}

function auth(req) {
  const header = req.headers.authorization || "";
  const token = header.startsWith("Bearer ") ? header.slice(7) : req.headers["x-webcode-token"];
  return token === TOKEN;
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  const p = u.pathname;

  try {
    if (!auth(req)) {
      res.writeHead(401, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: "未认证或令牌无效" }));
      return;
    }

    /* --- auth --- */
    if (p === "/api/auth/me" && req.method === "GET") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ authenticated: true }));
      return;
    }
    if (p === "/api/auth/login" && req.method === "POST") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true }));
      return;
    }

    /* --- workspace / usage --- */
    if (p === "/api/workspace" && req.method === "GET") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          workspace: WORKSPACE,
          model: MOCK ? "mock" : config.model,
          mock: MOCK,
          hasApiKey: Boolean(config.apiKey) || MOCK,
        })
      );
      return;
    }
    if (p === "/api/usage" && req.method === "GET") {
      const sessionId = u.searchParams.get("sessionId");
      const s = sessionId ? findSession(sessionId) : null;
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ usage, sessionUsage: s?.usage || null }));
      return;
    }

    /* --- settings --- */
    if (p === "/api/settings" && req.method === "GET") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          provider: "custom",
          baseUrl: config.baseUrl,
          model: config.model,
          authType: "bearer",
          apiKeyHeader: config.apiKeyHeader || "",
          thinking: config.thinking ? "on" : "off",
          reasoningEffort: null,
          maxSteps: config.maxSteps || null,
          hasApiKey: Boolean(config.apiKey) || MOCK,
          apiKeySet: Boolean(config.apiKey),
          envOverridden: false,
        })
      );
      return;
    }
    if (p === "/api/settings" && req.method === "PUT") {
      const body = await readBody(req);
      if (body.baseUrl !== undefined) config.baseUrl = String(body.baseUrl).trim();
      if (body.model !== undefined) config.model = String(body.model).trim();
      if (body.apiKey !== undefined && body.apiKey) config.apiKey = String(body.apiKey);
      if (body.thinking !== undefined) config.thinking = body.thinking === "on";
      if (body.maxSteps !== undefined) config.maxSteps = parseInt(body.maxSteps, 10) || 16;
      if (body.workspace !== undefined && String(body.workspace).trim()) {
        try {
          config.workspace = String(body.workspace).trim();
        } catch {}
      }
      fs.writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 2));
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true }));
      return;
    }

    /* --- sessions --- */
    if (p === "/api/sessions" && req.method === "GET") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(
        JSON.stringify({
          sessions: sessions.map((s) => ({
            id: s.id,
            title: s.title,
            createdAt: s.createdAt,
            updatedAt: s.updatedAt,
            usage: s.usage || null,
          })),
        })
      );
      return;
    }
    if (p === "/api/sessions" && req.method === "POST") {
      const body = await readBody(req);
      const session = {
        id: id("s"),
        title: (body.title || "新对话").slice(0, 60),
        createdAt: Date.now(),
        updatedAt: Date.now(),
        messages: [],
      };
      sessions.unshift(session);
      persistSessions();
      res.writeHead(201, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ session }));
      return;
    }
    const m = p.match(/^\/api\/sessions\/([^/]+)$/);
    if (m) {
      const sessionId = m[1];
      const s = findSession(sessionId);
      if (req.method === "GET") {
        if (!s) {
          res.writeHead(404, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ error: "会话不存在" }));
          return;
        }
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ session: s }));
        return;
      }
      if (req.method === "PATCH") {
        const body = await readBody(req);
        if (s && body.title) {
          s.title = String(body.title).slice(0, 60);
          persistSessions();
        }
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
        return;
      }
      if (req.method === "DELETE") {
        const idx = sessions.findIndex((x) => x.id === sessionId);
        if (idx === -1) {
          res.writeHead(404, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ error: "会话不存在" }));
          return;
        }
        sessions.splice(idx, 1);
        persistSessions();
        res.writeHead(200, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ ok: true }));
        return;
      }
    }

    /* --- approve / answer / abort --- */
    if (p === "/api/approve" && req.method === "POST") {
      const body = await readBody(req);
      const a = approvals.get(body.requestId);
      if (!a) {
        res.writeHead(404, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "审批请求不存在或已超时" }));
        return;
      }
      approvals.delete(body.requestId);
      a.resolve(Boolean(body.approved));
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true }));
      return;
    }
    if (p === "/api/answer" && req.method === "POST") {
      const body = await readBody(req);
      const q = questions.get(body.questionId);
      if (!q) {
        res.writeHead(404, { "Content-Type": "application/json" });
        res.end(JSON.stringify({ error: "问题不存在或已超时" }));
        return;
      }
      questions.delete(body.questionId);
      q.resolve(String(body.answer || "").slice(0, 2000));
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true }));
      return;
    }
    if (p === "/api/chat/abort" && req.method === "POST") {
      const body = await readBody(req);
      const e = runningAgents.get(body.sessionId);
      let ok = false;
      if (e?.controller) {
        e.controller.abort();
        ok = true;
      }
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok, running: false }));
      return;
    }

    /* --- chat (SSE) --- */
    if (p === "/api/chat") {
      if (req.method === "POST") {
        const body = await readBody(req);
        const content = String(body.content || "").trim();
        if (!content) {
          res.writeHead(400, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ error: "消息内容为空" }));
          return;
        }
        let sessionId = body.sessionId;
        let session = sessionId ? findSession(sessionId) : null;
        if (body.sessionId && !session) {
          res.writeHead(404, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ error: "会话不存在" }));
          return;
        }
        if (!session) {
          session = {
            id: id("s"),
            title: content.slice(0, 30),
            createdAt: Date.now(),
            updatedAt: Date.now(),
            messages: [],
          };
          sessions.unshift(session);
          persistSessions();
        }
        sessionId = session.id;
        if (isRunning(sessionId)) {
          res.writeHead(409, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ error: "该会话上一条消息还在处理中，请稍候" }));
          return;
        }

        const userMsg = {
          id: id("msg"),
          role: "user",
          parts: [{ type: "text", text: content }],
          createdAt: Date.now(),
        };
        const assistantMsg = {
          id: id("msg"),
          role: "assistant",
          parts: [],
          createdAt: Date.now(),
        };
        session.messages.push(userMsg, assistantMsg);
        session.updatedAt = Date.now();
        persistSessions();

        const e = emitter(sessionId);
        const send = (type, data) => {
          try {
            res.write(sse(type, data));
          } catch {}
        };
        const onEvent = (ev) => send(ev.type, ev);
        e.listeners.add(onEvent);

        res.writeHead(200, {
          "Content-Type": "text/event-stream; charset=utf-8",
          "Cache-Control": "no-cache, no-transform",
          Connection: "keep-alive",
          "X-Accel-Buffering": "no",
        });
        send("session", { sessionId });
        send("user_message", { message: userMsg, assistantMessageId: assistantMsg.id });

        req.on("close", () => {
          e.listeners.delete(onEvent);
        });

        runAgent(sessionId, assistantMsg.id).finally(() => {
          e.listeners.delete(onEvent);
          try {
            res.end();
          } catch {}
        });
        return;
      }

      /* GET: 重连订阅 */
      if (req.method === "GET") {
        const sessionId = u.searchParams.get("sessionId");
        if (!sessionId || !findSession(sessionId)) {
          res.writeHead(404, { "Content-Type": "application/json" });
          res.end(JSON.stringify({ error: "会话不存在" }));
          return;
        }
        res.writeHead(200, {
          "Content-Type": "text/event-stream; charset=utf-8",
          "Cache-Control": "no-cache, no-transform",
          Connection: "keep-alive",
          "X-Accel-Buffering": "no",
        });
        if (!isRunning(sessionId)) {
          res.end(
            sse("done", { messageId: "", finishReason: "already_done", sessionId })
          );
          return;
        }
        const e = emitter(sessionId);
        const send = (type, data) => {
          try {
            res.write(sse(type, data));
          } catch {}
        };
        const onEvent = (ev) => send(ev.type, ev);
        e.listeners.add(onEvent);
        const finish = () => {
          e.listeners.delete(onEvent);
          try {
            res.end();
          } catch {}
        };
        const onDone = (ev) => {
          if (ev.type === "done" || ev.type === "aborted" || ev.type === "error") finish();
        };
        e.listeners.add(onDone);
        req.on("close", () => {
          e.listeners.delete(onEvent);
          e.listeners.delete(onDone);
        });
        return;
      }
    }

    res.writeHead(404, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: "未找到" }));
  } catch (err) {
    res.writeHead(500, { "Content-Type": "application/json" });
    res.end(JSON.stringify({ error: String(err) }));
  }
});

/* ============ 启动 ============ */
server.listen(PORT, HOST, () => {
  console.log(
    [
      "",
      "╔══════════════════════════════════════════════╗",
      "║  WebCode Local Agent 已启动                  ║",
      `║  监听: http://${HOST}:${PORT}                      ║`,
      `║  模式: ${MOCK ? "mock（离线测试）" : config.model}                          ║`,
      `║  工作区: ${WORKSPACE.slice(0, 26)}                 ║`,
      "║  令牌: " + TOKEN.padEnd(30) + "║",
      "╚══════════════════════════════════════════════╝",
      "",
    ].join("\n")
  );
});
