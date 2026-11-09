/** Seconds per km → "m:ss". */
export function formatPace(s?: number) {
  if (!s) return '';
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/** "m:ss" → seconds per km, or undefined when not in that form. */
export function parsePace(text: string): number | undefined {
  const m = text.trim().match(/^(\d{1,2}):([0-5]\d)$/);
  return m ? Number(m[1]) * 60 + Number(m[2]) : undefined;
}
