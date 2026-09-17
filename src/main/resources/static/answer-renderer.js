/* Render model text using DOM nodes only. Raw HTML, images and executable URLs stay inert. */
function renderAnswerMarkdown(element, text) {
  element.replaceChildren();
  const inline = (parent, value, depth = 0) => {
    if (depth > 8) { parent.append(document.createTextNode(value)); return; }
    const tokens = /(`[^`\n]+`|\*\*[^\n]+?\*\*|\[[^\]\n]+\]\((?:https?:\/\/|reports\/)(?:[^\s()<>]|\([^()\s]*\))+\)|https?:\/\/[^\s<>\[\]，。；、]+)/g;
    let cursor = 0;
    for (const match of value.matchAll(tokens)) {
      parent.append(document.createTextNode(value.slice(cursor, match.index)));
      const token = match[0];
      let node;
      if (token.startsWith('`')) { node = document.createElement('code'); node.textContent = token.slice(1,-1); }
      else if (token.startsWith('**')) { node = document.createElement('strong'); inline(node, token.slice(2,-2), depth+1); }
      else if (/^https?:\/\//.test(token)) {
        node=document.createElement('a');node.href=token;node.target='_blank';node.rel='noopener noreferrer';node.textContent=token;
      }
      else {
        const boundary = token.indexOf(']('), target = token.slice(boundary+2,-1);
        try {
          if (target.startsWith('reports/')) {
            if (target.split('/').includes('..') || /[\\?#]/.test(target)) throw Error('Invalid report path');
            node = document.createElement('a'); node.href = '#'; node.textContent = token.slice(1,boundary);
            node.onclick = event => { event.preventDefault(); openReport(target.replace(/^reports\//,''), node.textContent); };
            parent.append(node); cursor = match.index + token.length; continue;
          }
          const url = new URL(target);
          if (!['http:','https:'].includes(url.protocol)) throw Error('Unsupported URL');
          node = document.createElement('a'); node.href = url.href; node.target = '_blank'; node.rel = 'noopener noreferrer';
          node.textContent = token.slice(1,boundary);
        } catch { node = document.createTextNode(token); }
      }
      parent.append(node); cursor = match.index + token.length;
    }
    parent.append(document.createTextNode(value.slice(cursor)));
  };
  const lines = String(text || '').replace(/\r\n/g,'\n').split('\n');
  const cells = line => line.trim().replace(/^\|/,'').replace(/\|$/,'').split('|').map(cell=>cell.trim());
  for (let i=0;i<lines.length;) {
    const line = lines[i];
    if (!line.trim()) { i++; continue; }
    if (/^\s*```/.test(line)) {
      const pre=document.createElement('pre'),code=document.createElement('code'),body=[];i++;
      while(i<lines.length&&!/^\s*```/.test(lines[i])) body.push(lines[i++]);
      if(i<lines.length)i++; code.textContent=body.join('\n');pre.append(code);element.append(pre);continue;
    }
    const heading=line.match(/^(#{1,6})\s+(.+)$/);
    if(heading){const node=document.createElement('h'+heading[1].length);inline(node,heading[2]);element.append(node);i++;continue;}
    if(/^\s*(---+|\*\*\*+)\s*$/.test(line)){element.append(document.createElement('hr'));i++;continue;}
    if(line.includes('|')&&i+1<lines.length&&cells(lines[i+1]).every(cell=>/^:?-{3,}:?$/.test(cell))){
      const wrapper=document.createElement('div');wrapper.className='answer-table';
      const table=document.createElement('table'),head=document.createElement('thead'),row=document.createElement('tr');
      for(const cell of cells(line)){const th=document.createElement('th');inline(th,cell);row.append(th);}head.append(row);table.append(head);i+=2;
      const body=document.createElement('tbody');
      while(i<lines.length&&lines[i].includes('|')&&lines[i].trim()){
        const tr=document.createElement('tr');for(const cell of cells(lines[i++])){const td=document.createElement('td');inline(td,cell);tr.append(td);}body.append(tr);
      }
      table.append(body);wrapper.append(table);element.append(wrapper);continue;
    }
    const item=line.match(/^\s*(?:([-*])|\d+[.)])\s+(.+)$/);
    if(item){
      const ordered=!item[1],list=document.createElement(ordered?'ol':'ul');
      while(i<lines.length){const part=lines[i].match(/^\s*(?:([-*])|\d+[.)])\s+(.+)$/);if(!part||!part[1]!==ordered)break;const li=document.createElement('li');inline(li,part[2]);list.append(li);i++;}
      element.append(list);continue;
    }
    if(/^>\s?/.test(line)){const quote=document.createElement('blockquote'),body=[];while(i<lines.length&&/^>\s?/.test(lines[i]))body.push(lines[i++].replace(/^>\s?/,''));inline(quote,body.join('\n'));element.append(quote);continue;}
    const p=document.createElement('p');inline(p,line);element.append(p);i++;
  }
}
