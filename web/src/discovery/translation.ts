export type Translate = (message: string) => string;

export const english: Translate = (message) => message;

export function interpolate(t: Translate, message: string, values: Record<string, string | number>): string {
  return t(message).replace(/\{(\w+)\}/g, (placeholder, key) => String(values[key] ?? placeholder));
}
