import axios, { AxiosError, type AxiosInstance, type AxiosRequestConfig, type AxiosResponse } from 'axios';
import { router } from '../router';
import { useUserStore } from '../stores/user';
import { createRequestId } from './id';

export interface ApiResponse<T = unknown> {
  code: number;
  message?: string;
  data?: T | null;
  requestId?: string;
  timestamp?: string;
}

export interface DownloadOptions extends AxiosRequestConfig {
  filename?: string;
}

let authRedirecting = false;

export class RequestError extends Error {
  status?: number;
  code?: number;
  requestId?: string;

  constructor(message: string, options: { status?: number; code?: number; requestId?: string } = {}) {
    super(message);
    this.name = 'RequestError';
    this.status = options.status;
    this.code = options.code;
    this.requestId = options.requestId;
  }
}

type RequestInstance = Omit<AxiosInstance, 'get' | 'post' | 'put' | 'delete' | 'request'> & {
  request<T = unknown, R = T>(config: AxiosRequestConfig): Promise<R>;
  get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T>;
  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>;
  put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>;
  delete<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T>;
};

export const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000
}) as RequestInstance;

function isApiResponse(value: unknown): value is ApiResponse {
  return Boolean(value && typeof value === 'object' && 'code' in value);
}

function getHeader(headers: AxiosResponse['headers'], key: string) {
  const lowerKey = key.toLowerCase();
  const value = headers[key] ?? headers[lowerKey];
  return Array.isArray(value) ? value[0] : value;
}

function parseFilename(contentDisposition?: string, fallback = 'download') {
  if (!contentDisposition) return fallback;
  const utf8Match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (utf8Match?.[1]) return decodeURIComponent(utf8Match[1]);
  const normalMatch = contentDisposition.match(/filename="?([^";]+)"?/i);
  return normalMatch?.[1] ? decodeURIComponent(normalMatch[1]) : fallback;
}

function formatApiMessage(message: string, requestId?: string) {
  return requestId ? `${message}（RequestId: ${requestId}）` : message;
}

async function parseBlobJson(blob: Blob) {
  const text = await blob.text();
  try {
    return JSON.parse(text) as ApiResponse;
  } catch (error) {
    throw new Error(`响应声明为 JSON 但解析失败：${text.slice(0, 200) || (error instanceof Error ? error.message : '空响应')}`);
  }
}

async function handleUnauthorized() {
  if (authRedirecting) return;
  authRedirecting = true;
  const userStore = useUserStore();
  userStore.clearAuthState();
  await router.replace({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } });
  window.setTimeout(() => { authRedirecting = false; }, 300);
}

request.interceptors.request.use((config) => {
  const userStore = useUserStore();
  config.headers = config.headers || {};
  config.headers['X-Request-Id'] = createRequestId();
  if (userStore.token) config.headers.Authorization = `Bearer ${userStore.token}`;
  return config;
});

request.interceptors.response.use(
  async <T>(response: AxiosResponse<ApiResponse<T> | T | Blob>) => {
    if (response.config.responseType === 'blob' && response.data instanceof Blob) {
      const contentType = getHeader(response.headers, 'content-type') || response.data.type;
      if (String(contentType).includes('application/json')) {
        const apiError = await parseBlobJson(response.data);
        if (apiError && apiError.code !== 0) {
          const message = formatApiMessage(apiError.message || '下载失败', apiError.requestId);
          if (apiError.code === 40100) await handleUnauthorized();
          else if (apiError.code === 40300) await router.replace('/403');
          return Promise.reject(new RequestError(message, { code: apiError.code, requestId: apiError.requestId }));
        }
      }
      return response as unknown as T;
    }

    const body = response.data;
    if (isApiResponse(body)) {
      if (body.code === 0) return (body.data ?? null) as T;
      if (body.code === 40100) {
        await handleUnauthorized();
        const message = formatApiMessage(body.message || '未登录或登录已过期', body.requestId);
        return Promise.reject(new RequestError(message, { code: body.code, requestId: body.requestId }));
      }
      if (body.code === 40300) {
        await router.replace('/403');
        const message = formatApiMessage(body.message || '无权限访问', body.requestId);
        return Promise.reject(new RequestError(message, { code: body.code, requestId: body.requestId }));
      }
      const message = formatApiMessage(body.message || '请求失败', body.requestId);
      return Promise.reject(new RequestError(message, { code: body.code, requestId: body.requestId }));
    }
    return body as T;
  },
  async (error: AxiosError<ApiResponse | Blob>) => {
    const status = error.response?.status;
    let message = '网络异常';
    let code: number | undefined;
    let requestId: string | undefined;
    if (error.response?.data instanceof Blob) {
      try {
        const apiError = await parseBlobJson(error.response.data);
        message = apiError.message || error.message || message;
        code = apiError.code;
        requestId = apiError.requestId;
      } catch (parseError) {
        message = parseError instanceof Error ? parseError.message : error.message || message;
      }
    } else {
      message = error.response?.data?.message || error.message || message;
      code = error.response?.data?.code;
      requestId = error.response?.data?.requestId || getHeader(error.response?.headers || {}, 'x-request-id');
    }
    message = formatApiMessage(message, requestId);
    if (status === 401) await handleUnauthorized();
    else if (status === 403) await router.replace('/403');
    return Promise.reject(new RequestError(message, { status, code, requestId }));
  }
);

export async function downloadFile(url: string, options: DownloadOptions = {}) {
  if (!url) throw new Error('下载地址为空');
  const { filename: requestedFilename, ...requestOptions } = options;
  // MinIO/S3 presigned URLs already carry their authentication in the query string.
  // Use a clean Axios request so the application JWT and X-Request-Id interceptors
  // are not added as a second authentication mechanism or leaked to object storage.
  const response = await axios.request<Blob>({
    timeout: request.defaults.timeout,
    ...requestOptions,
    url,
    method: requestOptions.method || 'GET',
    responseType: 'blob',
    transformResponse: [(data) => data]
  });
  saveBlob(response.data, response.headers, requestedFilename);
}

export async function downloadApiFile(url: string, options: DownloadOptions = {}) {
  if (!url) throw new Error('下载地址为空');
  const { filename: requestedFilename, ...requestOptions } = options;
  const response = await request.request<Blob, AxiosResponse<Blob>>({
    ...requestOptions,
    url,
    method: requestOptions.method || 'GET',
    responseType: 'blob',
    transformResponse: [(data) => data]
  });
  saveBlob(response.data, response.headers, requestedFilename);
}

function saveBlob(blob: Blob, headers: AxiosResponse['headers'], requestedFilename?: string) {
  const headerFilename = parseFilename(getHeader(headers, 'content-disposition'), requestedFilename || 'download');
  const filename = requestedFilename || headerFilename;
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement('a');
  try {
    link.href = objectUrl;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
  } finally {
    if (link.parentNode) document.body.removeChild(link);
    URL.revokeObjectURL(objectUrl);
  }
}

export function downloadTextFile(filename: string, data: string) {
  const blob = new Blob([data], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  try {
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
  } finally {
    if (link.parentNode) document.body.removeChild(link);
    URL.revokeObjectURL(url);
  }
}

export default request;



