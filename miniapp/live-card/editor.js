const beautify = require('../vendor/beautifier.min.js');
const colors = {plain:'#dceae0',keyword:'#ffb6bd',string:'#c2de9a',number:'#ffc88c',comment:'#98b6aa'};

const codeFont = '7px monospace';

function layoutSource(context, snippet, width) {
  let source = snippet.html || '';
  if (snippet.id === 'work-screen') {
    const body = source.match(/<pre>([\s\S]*?)<\/pre>/i);
    if (body) source = body[1].replace(/<\/?span\b[^>]*>/g,'');
  } else {
    source = beautify.html(source,{indent_size:2,indent_inner_html:true,wrap_line_length:48});
  }
  const rows = [];
  context.font = codeFont;
  for (const line of source.split('\n')) {
    let row = [], x = 0;
    const tokens = line.match(/\/\/.*|<!--.*?-->|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|<\/?[\w-]+|[\w$-]+|\s+|./gu) || [];
    for (const token of tokens) {
      let kind = 'plain';
      if (/^(\/\/|<!--)/.test(token)) kind = 'comment';
      else if (/^["']/.test(token)) kind = 'string';
      else if (/^(<|const$|let$|function$|return$|if$|else$)/.test(token)) kind = 'keyword';
      else if (/^\d/.test(token)) kind = 'number';
      for (const ch of token) {
        const advance = context.measureText(ch).width;
        if (x + advance > width) { rows.push(row); row = []; x = 0; }
        const last = row[row.length-1];
        if (last && last.color === colors[kind]) last.text += ch;
        else row.push({x,text:ch,color:colors[kind]});
        x += advance;
      }
    }
    rows.push(row);
  }
  return rows;
}
module.exports = {layoutSource,codeFont};
