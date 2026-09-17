/* Presentation only: permission decisions continue to be enforced by the server. */
const uiStyle=document.createElement('style');
uiStyle.textContent=`
.sidebar{display:grid;grid-template-rows:22% 27% minmax(0,1fr);gap:8px;padding:12px 12px 8px;background:#1c1c1c}
.sidebar .side-section{border:0;border-radius:0;background:transparent;min-height:0;height:100%}
.sidebar .side-toggle,.session-head{padding:10px 8px;font-size:13px;color:#bbb}.report-item{font-size:13px;line-height:1.45}.report-item small{display:none}.report-actions{opacity:0}.report-row:hover .report-actions,.report-row:focus-within .report-actions{opacity:1}
#sessionsSection{min-height:0}.session-head button{border:0;background:transparent;padding:5px;color:#bbb}.session-head button:hover{color:white;background:#333}.session-open{font-size:13px}.icon-svg{width:20px;height:20px;fill:none;stroke:currentColor;stroke-width:1.7;stroke-linecap:round;stroke-linejoin:round}
#requestNav{left:calc(260px + 22px);width:18px}.request-jump{width:14px;opacity:.7}#chat{padding-left:40px;padding-right:24px;padding-bottom:calc(var(--composer-height,200px) + 48px);scroll-padding-bottom:calc(var(--composer-height,200px) + 32px)}
#stopRun,#sessionMode{display:none!important}.run-controls{position:relative;min-height:30px}.mode-trigger{display:flex;align-items:center;gap:6px;border:0!important;background:transparent!important;color:#c8c8c8!important;font-size:13px}.mode-menu{position:absolute;bottom:calc(100% + 12px);left:0;z-index:80;width:330px;max-width:calc(100vw - 48px);padding:7px;border:1px solid #454545;border-radius:16px;background:#2b2b2b;box-shadow:0 12px 40px #0007}.mode-menu[hidden]{display:none}.mode-menu button{display:block;width:100%;border:0;text-align:left;background:transparent;padding:12px;border-radius:10px;color:#eee}.mode-menu button:hover,.mode-menu button[aria-selected=true]{background:#3a3a3a}.mode-menu small{display:block;white-space:normal;line-height:1.5;color:#aaa;margin-top:5px}.mode-menu strong{font-size:14px}
.work,.history-work{border:0!important;background:transparent!important;box-shadow:none!important;padding:0!important;color:#aaa;font-size:13px;margin-bottom:16px}.work summary,.history-work>summary{cursor:pointer;padding:8px 0}.work .flow,.history-work>div{padding:8px 12px;border-left:1px solid #444;margin:5px 0 12px}.flow-row{padding:4px 0}.candidate-label{color:#999;font-size:13px}.history-tool{padding:4px 0}.history-work .answer{font-size:13px;color:#aaa}.answer[hidden]{display:none}.preview-body .answer{padding:8px;font-size:14px;line-height:1.65}.preview-body .answer h1{font-size:22px}.preview-body .answer h2{font-size:18px}
@media(max-width:1100px){#requestNav{left:240px}}@media(max-width:900px){.sidebar{display:none}#requestNav{left:14px}#chat{grid-column:1;padding-left:38px}.mode-menu{width:300px}}
`;
document.head.append(uiStyle);
uiStyle.textContent+=`.sidebar .side-toggle .chev{display:none}.sidebar .side-toggle{font-size:13px;font-weight:500;color:#aaa;padding:8px}.sidebar .side-toggle:hover{color:#eee}.sidebar .side-content{gap:1px;padding:0 3px 6px}.sidebar .report-item{padding:6px 7px;font-size:13px;line-height:1.35}.sidebar .report-actions{display:flex;align-items:center;gap:0;padding-top:4px}.sidebar .report-row{grid-template-columns:minmax(0,1fr) 42px;gap:0}.mode-heading{padding:10px 12px 8px;font-size:13px;line-height:1.4;color:#aaa}`;
const sidebarTop=document.createElement('div');sidebarTop.className='sidebar-top';const sidebar=document.querySelector('.sidebar');sidebar.prepend(sidebarTop);sidebarTop.append(document.querySelector('#briefsSection'),document.querySelector('#reportsSection'));
uiStyle.textContent+=`.sidebar{grid-template-rows:49% minmax(0,1fr)}.sidebar-top{min-height:0;display:flex;flex-direction:column;gap:8px;overflow:hidden}.sidebar-top .side-section{height:auto;flex-shrink:1}.sidebar-top #briefsSection:not(.collapsed){flex:0 1 44%}.sidebar-top #reportsSection:not(.collapsed){flex:0 1 53%}.sidebar-top .side-section.collapsed{flex:0 0 auto}`;
const icon=body=>`<svg class="icon-svg" viewBox="0 0 24 24" aria-hidden="true">${body}</svg>`;
const newChatIcon=document.querySelector('#newChat');newChatIcon.innerHTML=icon('<path d="M13 5H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8"/><path d="m16 3 5 5M10 14l-1 4 4-1L22 8a2 2 0 0 0-5-5Z"/>');newChatIcon.title='New chat';newChatIcon.setAttribute('aria-label','New chat');
fileBtn.innerHTML=icon('<path d="M12 16V3m-5 5 5-5 5 5M4 14v5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-5"/>');fileBtn.title='Upload file';
eventsTab.innerHTML=icon('<rect x="3" y="5" width="18" height="16" rx="3"/><path d="M7 3v4m10-4v4M3 10h18m-13 4h2m4 0h2m-8 4h2"/>');
const modeTrigger=document.createElement('button');modeTrigger.type='button';modeTrigger.className='mode-trigger';modeTrigger.setAttribute('aria-haspopup','listbox');modeTrigger.setAttribute('aria-expanded','false');
const modeMenu=document.createElement('div');modeMenu.className='mode-menu';modeMenu.hidden=true;modeMenu.setAttribute('role','listbox');modeMenu.setAttribute('aria-label','Tool approval mode');
function updateModeLabel(){modeTrigger.textContent=sessionMode.value==='AUTO'?'Auto':'Default';for(const button of modeMenu.querySelectorAll('[role="option"]'))button.setAttribute('aria-selected',String(button.dataset.value===sessionMode.value));}
for(const [value,title,description] of [['DEFAULT','Default','Uses your settings, session rules, and tool risk checks. Asks you when approval is required.'],['AUTO','Auto','Applies Default checks and automatically reviews eligible actions. Sensitive actions requiring your input still ask for approval.']]){const button=document.createElement('button');button.type='button';button.dataset.value=value;button.setAttribute('role','option');const label=document.createElement('strong');label.textContent=title;const detail=document.createElement('small');detail.textContent=description;button.append(label,detail);button.onclick=async()=>{sessionMode.value=value;updateModeLabel();modeMenu.hidden=true;modeTrigger.setAttribute('aria-expanded','false');await sessionMode.onchange();};modeMenu.append(button);}
const modeHeading=document.createElement('div');modeHeading.className='mode-heading';modeHeading.textContent='How should Agent tools be approved?';modeMenu.prepend(modeHeading);
runControls.prepend(modeTrigger);runControls.append(modeMenu);updateModeLabel();modeTrigger.onclick=()=>{updateModeLabel();modeMenu.hidden=!modeMenu.hidden;modeTrigger.setAttribute('aria-expanded',String(!modeMenu.hidden));};
document.addEventListener('click',event=>{if(!modeMenu.contains(event.target)&&!modeTrigger.contains(event.target)){modeMenu.hidden=true;modeTrigger.setAttribute('aria-expanded','false');}});
document.addEventListener('keydown',event=>{if(event.key==='Escape'&&!modeMenu.hidden){modeMenu.hidden=true;modeTrigger.setAttribute('aria-expanded','false');modeTrigger.focus();}});
new ResizeObserver(()=>{document.documentElement.style.setProperty('--composer-height',composer.getBoundingClientRect().height+'px');}).observe(composer);
const originalSetRunning=setRunning;setRunning=function(value){originalSetRunning(value);sendIcon.textContent=value?'■':'↑';send.setAttribute('aria-label',value?'Stop response':'Send');send.title=value?'Stop response':'Send';};
send.addEventListener('click',event=>{if(running){event.preventDefault();event.stopImmediatePropagation();if(activeRunId)stopRun.onclick();}},true);
const originalHistoryMessage=historyMessage;const historyGroups=new Map();
historyMessage=function(message,accepted){
  if(message.meta){if(message.role==='USER'&&message.content?.some(b=>b.text?.startsWith('User request added while this run was executing:'))){const text=message.content.filter(b=>b.type==='TEXT').map(b=>b.text||'').join('\n').replace(/^User request added while this run was executing:\s*/, '');userMessage(text,message.id);historyGroups.delete(message.runId);}return;}
  if(cancelledPartialIds.has(message.id)){const row=document.createElement('div');row.className='turn agent';const label=document.createElement('div');label.className='candidate-label';label.textContent='Stopped · incomplete / unreviewed response';const answer=document.createElement('div');answer.className='answer';safeAnswer(answer,(message.content||[]).filter(b=>b.type==='TEXT').map(b=>b.text||'').join('\n'));row.append(label,answer);chat.append(row);return;}
  const hiddenCandidate=message.role==='ASSISTANT'&&!accepted.has(message.id)&&!(message.content||[]).some(block=>block.type==='TOOL_USE');
  if(message.role==='USER'){originalHistoryMessage(message,accepted);return;}
  let group=historyGroups.get(message.runId);
  if(!group||!group.isConnected){group=document.createElement('details');group.className='work';const summary=document.createElement('summary');const timing=historyRunDetails[message.runId]||{};const elapsed=Date.parse(timing.endedAt)-Date.parse(timing.startedAt);summary.textContent=Number.isFinite(elapsed)?`Worked for ${formatElapsed(elapsed)}`:'Worked';const body=document.createElement('div');body.className='flow';group.append(summary,body);chat.append(group);historyGroups.set(message.runId,group);}
  const recorded=window.restoredWorkEvents?.[message.runId];
  if(recorded?.length){if(!group.restored){group.restored=true;const render=workTimeline(group.lastElementChild);recorded.forEach(render);}if(accepted.has(message.id))originalHistoryMessage(message,accepted);return;}
  for(const block of message.content||[]){if(block.type==='PROGRESS'){const note=document.createElement('div');note.className='answer';safeAnswer(note,block.text||'');group.lastElementChild.append(note);}}
  if(hiddenCandidate)return;
  if(accepted.has(message.id)){originalHistoryMessage(message,accepted);return;}
  const body=group.lastElementChild;
  const text=(message.content||[]).filter(b=>b.type==='TEXT').map(b=>b.text||'').join('\n');
  if(text){const note=document.createElement('div');note.className='answer';safeAnswer(note,text);body.append(note);group.operation=null;}
  for(const block of message.content||[]){
    if(!['TOOL_USE','TOOL_RESULT'].includes(block.type))continue;
    if(!group.operation){const details=document.createElement('details'),summary=document.createElement('summary');details.className='history-tool';details.append(summary);body.append(details);group.operation=details;details.categories=new Set();}
    const details=group.operation;
    if(block.type==='TOOL_USE'){
      const name=block.name||'';details.categories.add(name.includes('read')?'Read files':/write|edit/.test(name)?'Edited files':name.includes('web')?'Searched the web':name.includes('x_search')?'Searched X':name.includes('ibkr')?'Read portfolio':name==='Skill'?'Loaded skills':'Used tools');
      details.firstElementChild.textContent=[...details.categories].join(', ');
      const row=document.createElement('div');row.textContent=name;details.append(row);
    }else if(block.error){const row=document.createElement('div');row.textContent='Operation failed';details.append(row);}
  }
};
const originalOpenSession=openSession;openSession=async function(id){await originalOpenSession(id);updateModeLabel();};
function confirmSessionTrash(){return new Promise(resolve=>{const dialog=document.createElement('dialog');dialog.style.cssText='background:#292929;color:#eee;border:1px solid #555;border-radius:18px;padding:24px;max-width:380px';const title=document.createElement('h3');title.textContent='Move session to Trash?';const detail=document.createElement('p');detail.textContent='The conversation and its files can be recovered from your computer’s Trash.';const cancel=document.createElement('button');cancel.textContent='Cancel';cancel.autofocus=true;const accept=document.createElement('button');accept.textContent='Move to Trash';for(const button of [cancel,accept])button.style.cssText='padding:9px 14px;margin:10px 8px 0 0;border-radius:9px;border:1px solid #555;background:#333;color:#eee;cursor:pointer';let settled=false;const finish=value=>{if(settled)return;settled=true;dialog.close();dialog.remove();resolve(value);};cancel.onclick=()=>finish(false);accept.onclick=()=>finish(true);dialog.addEventListener('cancel',event=>{event.preventDefault();finish(false);});dialog.append(title,detail,cancel,accept);document.body.append(dialog);dialog.showModal();});}
// Keep session and tools navigation reachable in narrow browser panels.
const panelStyle=document.createElement('style');panelStyle.textContent=`
.compact-panel-toggle{display:none;background:#333;color:#ddd;border:1px solid #555;border-radius:7px;padding:5px 9px;position:fixed;top:12px;z-index:55}
@media(max-width:1100px){#compactTools{display:block;right:12px}body.compact-tools .right-rail{display:grid;position:fixed;right:0;top:56px;bottom:0;width:min(400px,92vw);background:#242424;z-index:50}}
@media(max-width:900px){#compactSessions{display:block;left:12px}body.compact-sessions .sidebar{display:flex;position:fixed;left:0;top:56px;bottom:0;width:260px;background:#242424;z-index:50}}
`;document.head.append(panelStyle);
for(const [id,label,cls] of [['compactSessions','Sessions','compact-sessions'],['compactTools','Tools','compact-tools']]){
 const button=document.createElement('button');button.id=id;button.className='compact-panel-toggle';button.textContent=label;button.setAttribute('aria-label','Toggle '+label.toLowerCase());button.onclick=()=>{document.body.classList.toggle(cls);button.setAttribute('aria-expanded',String(document.body.classList.contains(cls)));};document.body.append(button);
}

// Keep model choices above their trigger; native select popups cannot be positioned reliably.
const modelPicker=document.createElement('div');modelPicker.className='model-picker';
primaryModel.before(modelPicker);modelPicker.append(primaryModel);primaryModel.hidden=true;
const modelButton=document.createElement('button');modelButton.type='button';modelButton.className='model-trigger';modelButton.setAttribute('aria-haspopup','listbox');
const modelOptions=document.createElement('div');modelOptions.className='model-options';modelOptions.hidden=true;modelOptions.setAttribute('role','listbox');modelOptions.setAttribute('aria-label','Primary model');modelPicker.append(modelButton,modelOptions);
function refreshModelPicker(){modelButton.textContent=(primaryModel.selectedOptions[0]?.textContent||'Choose model')+' ⌄';modelButton.disabled=primaryModel.disabled;modelButton.title=primaryModel.title;if(modelButton.disabled)modelOptions.hidden=true;modelButton.setAttribute('aria-expanded',String(!modelOptions.hidden));}
function closeModelPicker(){modelOptions.hidden=true;refreshModelPicker();}
modelButton.onclick=()=>{modelOptions.replaceChildren();for(const option of primaryModel.options){const button=document.createElement('button');button.type='button';button.textContent=(option.selected?'✓ ':'')+option.textContent;button.disabled=option.disabled;button.setAttribute('role','option');button.setAttribute('aria-selected',String(option.selected));button.onclick=async()=>{primaryModel.value=option.value;closeModelPicker();await primaryModel.onchange();};modelOptions.append(button);}modelOptions.hidden=!modelOptions.hidden;refreshModelPicker();if(!modelOptions.hidden)modelOptions.querySelector('button:not(:disabled)')?.focus();};
document.addEventListener('click',event=>{if(!modelPicker.contains(event.target))closeModelPicker();});
modelPicker.addEventListener('keydown',event=>{if(event.key==='Escape'){closeModelPicker();modelButton.focus();}if(['ArrowDown','ArrowUp'].includes(event.key)&&!modelOptions.hidden){event.preventDefault();const buttons=[...modelOptions.querySelectorAll('button:not(:disabled)')],index=buttons.indexOf(document.activeElement);buttons[(index+(event.key==='ArrowDown'?1:-1)+buttons.length)%buttons.length]?.focus();}});
new MutationObserver(refreshModelPicker).observe(primaryModel,{attributes:true,childList:true,subtree:true});refreshModelPicker();
// Approval remains in the conversation composer so users can still queue messages or stop.
composer.prepend(approvalBackdrop);approvalBackdrop.querySelector('[role="dialog"]').setAttribute('aria-modal','false');
const approvalDetails=document.createElement('details'),approvalDetailsLabel=document.createElement('summary');approvalDetailsLabel.textContent='See details';approvalSummary.before(approvalDetails);approvalDetails.append(approvalDetailsLabel,approvalSummary);
uiStyle.textContent+=`
.model-picker{margin-left:auto;position:relative}.model-trigger{border:0;background:transparent;color:#ccc;padding:8px;font:inherit;font-size:13px;cursor:pointer}.model-trigger:disabled{opacity:.55;cursor:default}.model-options{position:absolute;right:0;bottom:100%;min-width:185px;padding:6px;border:1px solid #4a4a4a;border-radius:12px;background:#2d2d2d;box-shadow:0 10px 30px #0006;z-index:90}.model-options[hidden]{display:none}.model-options button{display:block;width:100%;padding:10px;border:0;border-radius:7px;background:transparent;color:#eee;text-align:left;cursor:pointer}.model-options button:hover,.model-options button:focus-visible{background:#444}.model-options button:disabled{opacity:.4}
#approvalBackdrop{position:static;inset:auto;padding:0;margin:0 auto 10px;max-width:100%;background:none;backdrop-filter:none;box-shadow:none}#approvalBackdrop.open{display:block}.approval-card{width:100%;box-sizing:border-box;max-height:45vh;overflow:auto;border-radius:18px;box-shadow:none;padding:16px}.approval-subtitle{margin-bottom:10px}.approval-tool{padding:10px;margin-bottom:12px}.approval-tool details{margin-top:8px;color:#aaa;font-size:13px}.approval-summary{max-height:200px}.approval-actions{position:sticky;bottom:-16px;background:#2f2f2f;padding:10px 0}
.notifications-card{border:0;background:transparent;box-shadow:none;border-radius:0}.notifications-body{padding:8px}.notification-item{position:relative;border:0;border-radius:10px;padding:12px 38px 12px 10px;gap:4px}.notification-item:hover{background:#303030}.notification-item strong{font-size:14px;font-weight:500}.notification-item p{margin:0;font-size:12px;color:#aaa;line-height:1.5}.notification-item small{color:#888}.notification-item>button{position:absolute;right:6px;top:10px;border:0;background:transparent;color:#aaa;font-size:20px;cursor:pointer;padding:4px}.notification-item>button:hover{color:#ff8888}.notification-group-title{padding:12px 8px 4px;font-size:13px;font-weight:400;color:#999}
`;

const compactApprovalStyle=document.createElement('style');compactApprovalStyle.textContent=`
#approvalBackdrop{width:min(560px,100%);margin-left:auto;margin-right:auto}.approval-card{padding:12px;border-radius:16px;max-height:32vh;font-size:13px}.approval-title{font-size:16px}.approval-subtitle{display:none}.approval-tool{padding:8px;margin:8px 0}.approval-actions{padding:6px 0;bottom:-12px;gap:6px;flex-wrap:wrap}.approval-actions button{font-size:12px;padding:7px 10px}.approval-summary{max-height:90px;overflow:auto}
`;document.head.append(compactApprovalStyle);

// Keep the composer controls on one responsive row without squeezing the message body.
uiStyle.textContent+=`.bar{flex-wrap:wrap}.bar>button{flex-shrink:0}.model-picker{flex:0 1 auto;min-width:0}.model-trigger{white-space:nowrap}.bar #send,.bar #stopRun{margin-left:0;flex-shrink:0}#message{box-sizing:border-box;width:100%}`;
const approvalExplanation=document.createElement('div');approvalExplanation.className='approval-explanation';approvalDetails.before(approvalExplanation);
function explainApproval(){
 const a=activeApproval;if(!a)return;
 const name=a.rule?.toolName||'工具',args=a.arguments||{};
 const labels={quantity:'数量',qty:'数量',side:'方向',action:'操作',limit_price:'限价',price:'价格',symbol:'代码',ticker:'代码',contract_id:'合约 ID',order_type:'订单类型',outside_rth:'是否允许常规交易时段外执行',tif:'有效期',path:'文件路径',account_id:'账户'};
 const fields=[];function collect(value,depth=0){for(const [key,item] of Object.entries(value||{})){if(/token|password|api.?key/i.test(key)||item==null)continue;if(typeof item==='object'){if(depth<2)collect(item,depth+1);}else if(!['content','old_text','new_text'].includes(key))fields.push([labels[key]||key,item]);}}collect(args);
 approvalExplanation.textContent=(/create_order_instruction/.test(name)?'准备创建一条订单指令。':name==='write_file'?'准备写入文件。':name==='edit_file'?'准备修改文件。':'准备执行 '+name+'。')+(fields.length?'本次参数：'+fields.slice(0,8).map(([key,value])=>key+' = '+String(value).slice(0,160)).join('；')+'。':'')+(/create_order_instruction/.test(name)?'这会创建待确认的订单指令；请核对数量、方向和限价。':'详细操作与参数可展开查看。');
}
new MutationObserver(explainApproval).observe(approvalSummary,{childList:true,subtree:true,characterData:true});
uiStyle.textContent+=`.approval-explanation{font-size:13px;line-height:1.5;color:#ddd;margin:8px 0;overflow-wrap:anywhere}`;
