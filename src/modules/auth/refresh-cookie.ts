export const REFRESH_COOKIE_NAME = 'sistermesh_refresh';
export const REFRESH_TOKEN_TTL_SECONDS = 60 * 60 * 24 * 30;

interface HeaderResponse {
  setHeader(name: string, value: string): void;
}

interface CookieRequest {
  headers: { cookie?: string };
}

export function setRefreshCookie(response: HeaderResponse, token: string): void {
  response.setHeader(
    'Set-Cookie',
    `${REFRESH_COOKIE_NAME}=${encodeURIComponent(token)}; HttpOnly; Secure; SameSite=Strict; Path=/api/auth; Max-Age=${REFRESH_TOKEN_TTL_SECONDS}`,
  );
}

export function clearRefreshCookie(response: HeaderResponse): void {
  response.setHeader(
    'Set-Cookie',
    `${REFRESH_COOKIE_NAME}=; HttpOnly; Secure; SameSite=Strict; Path=/api/auth; Max-Age=0`,
  );
}

export function getRefreshCookie(request: CookieRequest): string | undefined {
  const entry = request.headers.cookie
    ?.split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${REFRESH_COOKIE_NAME}=`));
  if (!entry) return undefined;
  try {
    return decodeURIComponent(entry.slice(REFRESH_COOKIE_NAME.length + 1));
  } catch {
    return undefined;
  }
}
