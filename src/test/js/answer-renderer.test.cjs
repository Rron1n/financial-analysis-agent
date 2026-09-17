const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
class Element {constructor(tag){this.tag=tag;this.children=[];}append(...nodes){this.children.push(...nodes);}replaceChildren(){this.children=[];}}
const context={URL,document:{createElement:tag=>new Element(tag),createTextNode:text=>({text})}};
vm.createContext(context);vm.runInContext(fs.readFileSync('src/main/resources/static/answer-renderer.js','utf8'),context);
const root=new Element('div');context.renderAnswerMarkdown(root,'[Micron](https://investors.micron.com/default.aspx)、[SEC](https://www.sec.gov/file.htm)。 [nested](https://example.com/a_(b))');
const links=[];function walk(node){if(node.tag==='a')links.push(node.href);(node.children||[]).forEach(walk);}walk(root);
assert.deepEqual(links,['https://investors.micron.com/default.aspx','https://www.sec.gov/file.htm','https://example.com/a_(b)']);
console.log('PASS: Chinese adjacent citations and balanced URL parentheses');
