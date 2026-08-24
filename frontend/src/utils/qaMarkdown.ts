function escapeHtml(value: string) {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function renderInline(value: string) {
  return escapeHtml(value)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
}

function normalizeText(value: string) {
  return value
    .replace(/\r\n?/g, '\n')
    .replace(/^\s*(\|\s*)+\s*$/gm, '')
    .replace(/^\s*---+\s*$/gm, '\n\n')
    .replace(/\s+(#{1,6}\s+)/g, '\n$1')
    .replace(/\s+([一二三四五六七八九十\d]+[、.．]\s*\*\*[^*]+?\*\*)/g, '\n\n$1')
    .replace(/\s+(\*\*[一二三四五六七八九十\d]+[、.．][^*]+?\*\*)/g, '\n\n$1\n')
    .trim();
}

function splitPipeRow(line: string) {
  return line
    .replace(/^\s*\|+|\|+\s*$/g, '')
    .split('|')
    .map((cell) => cell.trim())
    .filter(Boolean);
}

function isTableSeparator(line: string) {
  return /^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?$/.test(line);
}

function isPipeRow(line: string) {
  return line.includes('|') && splitPipeRow(line).length >= 2;
}

function splitSlashHeader(line: string) {
  const parts = line.split(/\s+\/\s+/).map((part) => part.trim()).filter(Boolean);
  if (parts.length < 3 || parts.length > 6) return [];
  if (parts.some((part) => part.length > 24)) return [];
  return parts;
}

function padRow(row: string[], size: number) {
  return Array.from({ length: size }, (_, index) => row[index] || '');
}

function renderTable(headers: string[], rows: string[][]) {
  const width = headers.length;
  const body = rows
    .filter((row) => row.some(Boolean))
    .map((row) => `<tr>${padRow(row, width).map((cell) => `<td>${renderInline(cell)}</td>`).join('')}</tr>`)
    .join('');
  if (!body) return '';
  return `<table><thead><tr>${headers.map((cell) => `<th>${renderInline(cell)}</th>`).join('')}</tr></thead><tbody>${body}</tbody></table>`;
}

function renderMalformedSlashTable(lines: string[], start: number, headers: string[]) {
  const separatorOffset = lines.slice(start + 1, start + 10).findIndex((line) => isTableSeparator(line.trim()));
  if (separatorOffset < 0) return { html: '', next: start };

  const rows: string[][] = [];
  let index = start + separatorOffset + 2;
  while (index < lines.length && isPipeRow(lines[index].trim())) {
    const row = splitPipeRow(lines[index].trim());
    if (row.length === headers.length) rows.push(row);
    index += 1;
  }
  return { html: renderTable(headers, rows), next: index };
}
function renderPipeTable(lines: string[], start: number, fallbackHeaders?: string[]) {
  const rows: string[][] = [];
  let index = start;
  while (index < lines.length && isPipeRow(lines[index].trim())) {
    const row = splitPipeRow(lines[index].trim());
    if (!row.every((cell) => /^:?-{3,}:?$/.test(cell))) rows.push(row);
    index += 1;
  }

  if (!rows.length) return { html: '', next: start };
  if (fallbackHeaders?.length) {
    return { html: renderTable(fallbackHeaders, rows), next: index };
  }
  if (rows.length >= 2 && rows[0].length === rows[1].length && rows[0].every((cell) => cell.length <= 24)) {
    return { html: renderTable(rows[0], rows.slice(1)), next: index };
  }
  return { html: `<p>${renderInline(rows.map((row) => row.join(' / ')).join('；'))}</p>`, next: index };
}

function renderFence(lines: string[], start: number) {
  const first = lines[start].trim();
  const lang = first.replace(/^```/, '').trim();
  const body: string[] = [];
  let index = start + 1;
  while (index < lines.length && !lines[index].trim().startsWith('```')) {
    body.push(lines[index]);
    index += 1;
  }
  return {
    html: `<pre class="qa-code"><code>${lang ? `<span>${escapeHtml(lang)}</span>\n` : ''}${escapeHtml(body.join('\n'))}</code></pre>`,
    next: index < lines.length ? index + 1 : index
  };
}

export function renderQaMarkdown(value: string) {
  const lines = normalizeText(value).split('\n');
  const html: string[] = [];

  for (let i = 0; i < lines.length;) {
    const line = lines[i].trim();
    if (!line) {
      i += 1;
      continue;
    }

    if (line.startsWith('```')) {
      const block = renderFence(lines, i);
      html.push(block.html);
      i = block.next;
      continue;
    }

    const heading = /^(#{1,6})\s+(.+)$/.exec(line);
    if (heading) {
      const level = Math.min(heading[1].length + 2, 6);
      html.push(`<h${level}>${renderInline(heading[2])}</h${level}>`);
      i += 1;
      continue;
    }

    if (isPipeRow(line) && lines[i + 1] && isTableSeparator(lines[i + 1].trim())) {
      const headers = splitPipeRow(line);
      const block = renderPipeTable(lines, i + 2, headers);
      if (block.html) html.push(block.html);
      i = block.next;
      continue;
    }

    const slashHeader = splitSlashHeader(line);
    if (slashHeader.length) {
      const malformedBlock = renderMalformedSlashTable(lines, i, slashHeader);
      if (malformedBlock.html) {
        html.push(malformedBlock.html);
        i = malformedBlock.next;
        continue;
      }
      if (lines[i + 1] && isPipeRow(lines[i + 1].trim())) {
        const block = renderPipeTable(lines, i + 1, slashHeader);
        html.push(block.html || `<p>${renderInline(line)}</p>`);
        i = block.next;
        continue;
      }
    }

    if (isPipeRow(line)) {
      const block = renderPipeTable(lines, i);
      html.push(block.html);
      i = block.next;
      continue;
    }

    if (/^[-*+]\s+/.test(line) || /^\d+[.)、]\s+/.test(line)) {
      const ordered = /^\d+[.)、]\s+/.test(line);
      const items: string[] = [];
      while (i < lines.length) {
        const current = lines[i].trim();
        const match = ordered ? /^\d+[.)、]\s+(.+)$/.exec(current) : /^[-*+]\s+(.+)$/.exec(current);
        if (!match) break;
        items.push(`<li>${renderInline(match[1])}</li>`);
        i += 1;
      }
      html.push(`<${ordered ? 'ol' : 'ul'}>${items.join('')}</${ordered ? 'ol' : 'ul'}>`);
      continue;
    }

    const paragraph = [line];
    i += 1;
    while (i < lines.length) {
      const next = lines[i].trim();
      if (!next || next.startsWith('```') || /^#{1,6}\s+/.test(next) || /^[-*+]\s+/.test(next) || /^\d+[.)、]\s+/.test(next) || isPipeRow(next) || splitSlashHeader(next).length) break;
      paragraph.push(next);
      i += 1;
    }
    html.push(`<p>${renderInline(paragraph.join(' '))}</p>`);
  }

  return html.join('');
}
