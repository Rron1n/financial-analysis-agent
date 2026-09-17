const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
class Node {
  constructor(tag){this.tag=tag;this.children=[];this.value='';}
  append(...nodes){this.children.push(...nodes);}
  replaceChildren(){this.children=[];this.value='';}
  set textContent(value){this.value=value;this.children=[];}
  get textContent(){return this.value+this.children.map(node=>node.textContent).join('');}
}
const context={URL,document:{createElement:tag=>new Node(tag),createTextNode:text=>{const node=new Node('#text');node.textContent=text;return node;}}};
vm.createContext(context);vm.runInContext(fs.readFileSync('src/main/resources/static/answer-renderer.js','utf8'),context);
const all=node=>[node,...node.children.flatMap(all)];
test('renders headings, emphasis, lists and tables as semantic DOM',()=>{
  const root=new Node('root');context.renderAnswerMarkdown(root,'# Result\n\n**Summary**\n- first\n- second\n\n|Account|View|\n|---|---|\n|Example|Mixed|');
  const nodes=all(root);for(const tag of ['h1','strong','ul','li','table','th','td'])assert.ok(nodes.some(node=>node.tag===tag),tag);
  assert.equal(nodes.filter(node=>node.tag==='li').length,2);
});
test('HTML and executable links stay inert; citations are safe new-tab links',()=>{
  const root=new Node('root');context.renderAnswerMarkdown(root,'<img src=x onerror=alert(1)>\n[bad](javascript:alert(1))\n[Source](https://example.com/page)');
  const nodes=all(root);assert.ok(!nodes.some(node=>['img','script'].includes(node.tag)));
  const links=nodes.filter(node=>node.tag==='a');assert.equal(links.length,1);assert.equal(links[0].rel,'noopener noreferrer');
  assert.ok(root.textContent.includes('<img'));assert.ok(root.textContent.includes('javascript:'));
});
test('unfinished streamed fences remain text and replace without duplication',()=>{
  const root=new Node('root');context.renderAnswerMarkdown(root,'```html\n<script>alert(1)</script>');
  assert.equal(all(root).filter(node=>node.tag==='script').length,0);
  context.renderAnswerMarkdown(root,'## Done');assert.equal(root.textContent,'Done');assert.equal(root.children.length,1);
});
