import { describe, expect, it } from 'vitest';
import i18n from './i18n';
import { describeContribution, describeReason, formatDay, formatPoints } from './today';

const t = i18n.getFixedT('en');

describe('formatPoints', () => {
  it('rounds to whole points with a sign', () => {
    expect(formatPoints(6.2)).toBe('+6');
    expect(formatPoints(-2.6)).toBe('−3');
    expect(formatPoints(0.4)).toBe('0');
    expect(formatPoints(null)).toBe('–');
  });
});

describe('formatDay', () => {
  it('keeps the calendar date whatever the device timezone', () => {
    expect(formatDay('2026-11-16', 'en')).toContain('16');
    expect(formatDay('2026-11-16', 'en')).toContain('Mon');
  });
});

describe('describeContribution', () => {
  it('says HRV as a percentage of the 30-day baseline', () => {
    expect(describeContribution({ feature: 'ln_rmssd_z', measured: 46, baseline: 50, percentVsBaseline: -8 }, t)).toBe(
      'HRV 46 ms, 8% below your 30-day baseline (50 ms)',
    );
    expect(describeContribution({ feature: 'ln_rmssd_z' }, t)).toBe('HRV compared with your 30-day baseline');
  });
  it('says resting HR and breathing as differences from the average', () => {
    expect(describeContribution({ feature: 'rhr_dev', value: 3.2 }, t)).toBe('Resting HR 3 bpm above your 30-day average');
    expect(describeContribution({ feature: 'resp_dev', value: -0.42 }, t)).toBe('Breathing rate 0.4 breaths/min below your 30-day average');
    expect(describeContribution({ feature: 'sleep_perf', value: 91.6 }, t)).toBe('Slept 92% of your sleep need');
  });
});

describe('describeReason', () => {
  it('names the reason and, for yesterday’s strain, why it is unknown', () => {
    expect(describeReason({ reason: 'NO_HRV' }, t)).toBe('No overnight HRV, or not enough nights yet for an HRV baseline');
    expect(describeReason({ reason: 'NO_STRAIN_YESTERDAY', detail: 'MISSING_WORKOUT_HR' }, t)).toBe(
      "Yesterday's strain is unknown: a workout was recorded without heart rate",
    );
  });
});
