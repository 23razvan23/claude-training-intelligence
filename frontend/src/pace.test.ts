import { describe, expect, it } from 'vitest';
import { formatPace, parsePace } from './pace';

describe('formatPace', () => {
  it('writes seconds per km as m:ss with zero-padded seconds', () => {
    expect(formatPace(300)).toBe('5:00');
    expect(formatPace(275)).toBe('4:35');
    expect(formatPace(367)).toBe('6:07');
  });

  it('writes nothing for a missing or zero pace', () => {
    expect(formatPace(undefined)).toBe('');
    expect(formatPace(0)).toBe('');
  });
});

describe('parsePace', () => {
  it('reads m:ss, ignoring surrounding spaces', () => {
    expect(parsePace('4:35')).toBe(275);
    expect(parsePace(' 5:00 ')).toBe(300);
    expect(parsePace('10:05')).toBe(605);
  });

  it('refuses anything that is not m:ss', () => {
    for (const bad of ['', '435', '4:5', '4:60', '4.35', 'abc', '4:35:10', '-4:35']) {
      expect(parsePace(bad), bad).toBeUndefined();
    }
  });

  it('round-trips with formatPace', () => {
    for (const s of [185, 240, 299, 301, 599]) {
      expect(parsePace(formatPace(s))).toBe(s);
    }
  });
});
