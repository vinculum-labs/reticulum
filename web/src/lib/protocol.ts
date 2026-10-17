// WebSocket client for the game's local web server. Requests get a reply matched by id; everything else the
// server sends (snapshots, metrics, focus, connection changes) goes to the message listener.

type Listener = (message: any) => void;

const TAB = Math.random().toString(36).slice(2, 8);
/**
 * How long a reply may take. Edits are sent one after another, so one never answered would hold up every later one;
 * past this it counts as failed (the next snapshot shows what the server actually did).
 */
const TIMEOUT_MS = 30_000;

export class Connection {
  private socket: WebSocket | null = null;
  private seq = 0;
  private readonly pending = new Map<string, { resolve: (m: any) => void; reject: (e: Error) => void }>();
  private retry = 500;

  constructor(
    private readonly onMessage: Listener,
    private readonly onOpenChange: (open: boolean) => void,
  ) {}

  connect(): void {
    // Beside the page, wherever it is served (its own host's root, or /reticulum/).
    const url = new URL("ws", location.href);
    url.protocol = location.protocol === "https:" ? "wss:" : "ws:";
    const socket = new WebSocket(url);
    this.socket = socket;
    socket.onopen = () => {
      this.retry = 500;
      this.onOpenChange(true);
      this.send({ op: "hello" }).catch(() => {});
    };
    socket.onclose = () => {
      this.onOpenChange(false);
      for (const p of this.pending.values()) p.reject(new Error("Connection to the game lost"));
      this.pending.clear();
      setTimeout(() => this.connect(), this.retry);
      this.retry = Math.min(this.retry * 2, 5000);
    };
    socket.onmessage = (event) => {
      const message = JSON.parse(event.data);
      if (message.type === "response" && message.id && this.pending.has(message.id)) {
        const p = this.pending.get(message.id)!;
        this.pending.delete(message.id);
        message.ok ? p.resolve(message) : p.reject(new Error(message.error ?? "Request failed"));
      } else if (message.type === "hello") {
        this.onMessage(message);
      } else {
        this.onMessage(message);
      }
    };
  }

  /** Sends a request; "hello" is answered by the game client itself, everything else by the server. */
  send(request: Record<string, unknown>): Promise<any> {
    const id = `${TAB}-${++this.seq}`;
    return new Promise((resolve, reject) => {
      if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
        reject(new Error("Not connected to the game"));
        return;
      }
      if (request.op !== "hello") {
        const timer = setTimeout(() => {
          if (this.pending.delete(id)) reject(new Error("The game didn't answer in time"));
        }, TIMEOUT_MS);
        this.pending.set(id, {
          resolve: (m) => (clearTimeout(timer), resolve(m)),
          reject: (e) => (clearTimeout(timer), reject(e)),
        });
      }
      this.socket.send(JSON.stringify({ id, ...request }));
      if (request.op === "hello") resolve({});
    });
  }
}
