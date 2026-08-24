import type { ApiErrorBody } from "./types";

/**
 * Erro de domínio devolvido pela API. RestExceptionHandler traduz as exceptions
 * do service em 404/409/422 com corpo ApiError; `mensagem` já vem escrita para
 * o operador, então é ela que o toast mostra.
 */
export class ApiErrorException extends Error {
  readonly codigo: string;
  readonly status: number;

  constructor(codigo: string, mensagem: string, status: number) {
    super(mensagem);
    this.name = "ApiErrorException";
    this.codigo = codigo;
    this.status = status;
  }
}

/** 404 é resposta normal em /por-tag/{tag} — quem chama decide o que fazer. */
export class NotFoundError extends ApiErrorException {}

async function parseError(res: Response): Promise<ApiErrorException> {
  let codigo = "ERRO_HTTP";
  let mensagem = `Falha na comunicação com a API (HTTP ${res.status}).`;
  try {
    const body = (await res.json()) as ApiErrorBody;
    if (body && typeof body.mensagem === "string") {
      codigo = body.codigo ?? codigo;
      mensagem = body.mensagem;
      if (body.campos?.length) {
        mensagem += " — " + body.campos.map((c) => `${c.campo}: ${c.erro}`).join("; ");
      }
    }
  } catch {
    // resposta sem corpo JSON (404 do Spring, 500 cru): fica a mensagem padrão
  }
  const Cls = res.status === 404 ? NotFoundError : ApiErrorException;
  return new Cls(codigo, mensagem, res.status);
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      method,
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiErrorException(
      "SEM_CONEXAO",
      "Não foi possível falar com a API. Confirme se ela está de pé em localhost:8080.",
      0,
    );
  }
  if (!res.ok) throw await parseError(res);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const http = {
  get: <T>(path: string) => request<T>("GET", path),
  post: <T>(path: string, body?: unknown) => request<T>("POST", path, body),
  put: <T>(path: string, body: unknown) => request<T>("PUT", path, body),
  patch: <T>(path: string, body?: unknown) => request<T>("PATCH", path, body),
  del: <T>(path: string) => request<T>("DELETE", path),
};
