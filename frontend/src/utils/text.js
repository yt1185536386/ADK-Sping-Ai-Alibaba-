// 提取文本中的 [AMAP]{...}[/AMAP] 结构化标记：返回 { clean: 去除标记后的文本, specs: 解析出的地图规格数组 }
export function extractAmap(text) {
  const specs = [];
  const clean = String(text).replace(/\[AMAP\]([\s\S]*?)\[\/AMAP\]/g, (m, json) => {
    try { specs.push(JSON.parse(json)); } catch (e) { /* 解析失败忽略，仍移除标记 */ }
    return '';
  }).replace(/\n{3,}/g, '\n\n').trimEnd();
  return { clean, specs };
}

// 极简 Markdown：表格、粗体、行内代码、换行
export function renderMarkdown(text) {
  let html = esc(text);
  const lines = html.split('\n');
  const out = [];
  let table = [];
  const flushTable = () => {
    if (!table.length) return;
    const rows = table.filter(l => !/^\s*\|?[\s:|-]+\|?\s*$/.test(l));
    if (rows.length) {
      out.push('<table>');
      rows.forEach((r, i) => {
        const cells = r.replace(/^\||\|$/g, '').split('|').map(c => c.trim());
        out.push('<tr>' + cells.map(c => (i === 0 ? '<th>' : '<td>') + c + (i === 0 ? '</th>' : '</td>')).join('') + '</tr>');
      });
      out.push('</table>');
    }
    table = [];
  };
  lines.forEach(line => {
    if (/^\s*\|.*\|\s*$/.test(line)) { table.push(line); return; }
    flushTable();
    out.push(line);
  });
  flushTable();
  return out.join('\n')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>');
}

export function esc(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
