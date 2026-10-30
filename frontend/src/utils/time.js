// 全系统统一时间显示格式：YYYY-MM-DD HH:mm:ss
// 后端已统一输出该格式（见 JacksonTimeConfig），这里做兜底归一：
// ISO「2026-10-28T08:49:59.270+08:00」、带毫秒、缺秒等输入都规整成标准格式。

/**
 * 把时间值格式化为「YYYY-MM-DD HH:mm:ss」；无法识别时原样返回。
 * @param {string|Date|number|null|undefined} v
 * @returns {string}
 */
export function fmtTime(v) {
  if (v === null || v === undefined || v === '') return '';
  if (v instanceof Date) return pad2Date(v);
  const m = String(v).match(/(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2})(?::(\d{2}))?/);
  if (m) return `${m[1]} ${m[2]}:${m[3] || '00'}`;
  return String(v);
}

/** 只取日期部分「YYYY-MM-DD」 */
export function fmtDate(v) {
  const s = fmtTime(v);
  return s.length >= 10 ? s.slice(0, 10) : s;
}

function pad2Date(d) {
  const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}
