// ==================== 场景选择 ====================
const modelSelect = document.getElementById('modelSelect');
const selectorHint = document.getElementById('selectorHint');
const hintMap = {
  '/chat': 'Spring AI：RAG 检索 + 引用来源 + 工具调用',
};
const placeholderMap = {
  '/chat': '输入您的医疗问题，如：高血压患者的降压目标是多少？',
};

modelSelect.addEventListener('change', () => {
  const path = modelSelect.value;
  selectorHint.textContent = hintMap[path] || '';
  msgInput.placeholder = placeholderMap[path] || '';
});

// ==================== Tab 切换 ====================
document.querySelectorAll('.tab').forEach(btn => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
    btn.classList.add('active');
    document.getElementById('panel-' + btn.dataset.tab).classList.add('active');
  });
});

// ==================== API 状态检测 ====================
const statusPill = document.getElementById('apiStatus');
async function checkApi() {
  try {
    const res = await fetch('/rag/diag');
    if (res.ok) {
      statusPill.classList.add('ok');
      statusPill.classList.remove('err');
      statusPill.innerHTML = '<span class="dot"></span> 后端就绪';
    } else {
      throw new Error('bad status');
    }
  } catch {
    statusPill.classList.add('err');
    statusPill.classList.remove('ok');
    statusPill.innerHTML = '<span class="dot"></span> 后端未启动';
  }
}
checkApi();

// ==================== 登录鉴权 ====================
const authArea = document.getElementById('authArea');
const authLoggedIn = document.getElementById('authLoggedIn');
const authLoggedOut = document.getElementById('authLoggedOut');
const authUser = document.getElementById('authUser');
const loginBtn = document.getElementById('loginBtn');
const logoutBtn = document.getElementById('logoutBtn');
const loginModal = document.getElementById('loginModal');
const loginClose = document.getElementById('loginClose');
const loginForm = document.getElementById('loginForm');
const loginError = document.getElementById('loginError');

async function refreshAuthStatus() {
  try {
    const res = await fetch('/auth/status', { credentials: 'same-origin' });
    const data = await res.json();
    if (data.loggedIn) {
      authLoggedIn.classList.remove('hidden');
      authLoggedOut.classList.add('hidden');
      authUser.textContent = data.username;
    } else {
      authLoggedIn.classList.add('hidden');
      authLoggedOut.classList.remove('hidden');
    }
  } catch {
    authLoggedIn.classList.add('hidden');
    authLoggedOut.classList.remove('hidden');
  }
}

function showLoginModal() {
  loginModal.classList.remove('hidden');
  loginError.classList.add('hidden');
  loginForm.reset();
}

function hideLoginModal() {
  loginModal.classList.add('hidden');
}

loginBtn.addEventListener('click', showLoginModal);
loginClose.addEventListener('click', hideLoginModal);
loginModal.addEventListener('click', (e) => {
  if (e.target === loginModal) hideLoginModal();
});

loginForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const formData = new FormData(loginForm);
  try {
    const res = await fetch('/login', {
      method: 'POST',
      body: formData,
      credentials: 'same-origin',
      redirect: 'manual'
    });
    if (res.ok || res.type === 'opaqueredirect') {
      hideLoginModal();
      await refreshAuthStatus();
    } else {
      loginError.classList.remove('hidden');
    }
  } catch {
    loginError.classList.remove('hidden');
  }
});

logoutBtn.addEventListener('click', async () => {
  try {
    await fetch('/logout', { method: 'POST', credentials: 'same-origin' });
  } catch {}
  await refreshAuthStatus();
});

refreshAuthStatus();

// ==================== 聊天 ====================
const chatArea = document.getElementById('chatArea');
const msgInput = document.getElementById('msgInput');
const sendBtn = document.getElementById('sendBtn');

let currentSessionId = null;

async function ensureSession() {
  if (currentSessionId) return currentSessionId;
  try {
    const res = await fetch('/chat/session/new', { method: 'POST' });
    const data = await res.json();
    currentSessionId = data.sessionId;
    return currentSessionId;
  } catch {
    return null;
  }
}

function resetSession() {
  currentSessionId = null;
}

function splitAnswer(raw) {
  const refIdx = raw.indexOf('【引用来源】');
  let answer = raw, refs = null;
  if (refIdx !== -1) {
    answer = raw.substring(0, refIdx).trim();
    refs = raw.substring(refIdx);
  }
  return { answer, refs };
}

function addMsg(role, text) {
  const welcome = chatArea.querySelector('.chat-welcome');
  if (welcome) welcome.remove();

  const wrap = document.createElement('div');
  wrap.className = 'msg ' + role;

  const avatar = document.createElement('div');
  avatar.className = 'msg-avatar';
  avatar.textContent = role === 'user' ? '👤' : '🩺';

  const bubble = document.createElement('div');
  bubble.className = 'msg-bubble';

  if (role === 'ai') {
    const { answer, refs } = splitAnswer(text);
    bubble.textContent = answer;
    if (refs) {
      const refsDiv = document.createElement('div');
      refsDiv.className = 'refs-block';
      refsDiv.innerHTML = '<strong>📎 引用来源</strong><br>' + refs.replace(/\n/g, '<br>');
      bubble.appendChild(refsDiv);
    }
  } else {
    bubble.textContent = text;
  }

  wrap.appendChild(avatar);
  wrap.appendChild(bubble);
  chatArea.appendChild(wrap);
  chatArea.scrollTop = chatArea.scrollHeight;
  return bubble;
}

function addStreamingBubble() {
  const welcome = chatArea.querySelector('.chat-welcome');
  if (welcome) welcome.remove();

  const wrap = document.createElement('div');
  wrap.className = 'msg ai';
  wrap.id = 'streamingMsg';

  const avatar = document.createElement('div');
  avatar.className = 'msg-avatar';
  avatar.textContent = '🩺';

  const bubble = document.createElement('div');
  bubble.className = 'msg-bubble';
  bubble.style.whiteSpace = 'pre-wrap';

  wrap.appendChild(avatar);
  wrap.appendChild(bubble);
  chatArea.appendChild(wrap);
  chatArea.scrollTop = chatArea.scrollHeight;
  return bubble;
}

function removeStreamingBubble() {
  const t = document.getElementById('streamingMsg');
  if (t) t.remove();
}

// 流结束后这条消息要留在页面上，只摘掉「正在流」的标记；
// 直接 remove() 会把刚打完的答案整条删掉，页面回到只有提问的状态。
function finishStreamingBubble() {
  const t = document.getElementById('streamingMsg');
  if (t) t.removeAttribute('id');
}

async function sendMessage(text) {
  if (!text || !text.trim()) return;

  const apiPath = modelSelect.value;
  const isRagChat = apiPath === '/chat';

  addMsg('user', text);
  sendBtn.disabled = true;

  if (isRagChat) {
    await sendRagStream(text);
  } else {
    await sendPlainMessage(apiPath, text);
  }

  sendBtn.disabled = false;
  msgInput.value = '';
  msgInput.style.height = 'auto';
}

async function sendPlainMessage(apiPath, text) {
  try {
    const res = await fetch(apiPath + '?message=' + encodeURIComponent(text));
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const body = await res.text();
    const bubble = addMsg('ai', body);
    bubble.style.borderLeft = '3px solid #9b59b6';
  } catch (e) {
    addMsg('ai', '❌ 请求失败：' + e.message + '\n\n请检查后端是否已启动（默认端口 8080）。');
  }
}

async function sendRagStream(text) {
  const sid = await ensureSession();
  const bubble = addStreamingBubble();

  const url = '/chat/stream?message=' + encodeURIComponent(text)
    + (sid ? '&sessionId=' + sid : '');

  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error('HTTP ' + res.status);

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let refsRendered = false;
    let answerText = '';
    let refsText = '';

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      const events = buffer.split(/\n\n/);
      buffer = events.pop();

      for (const rawEvent of events) {
        const lines = rawEvent.split('\n');
        let eventName = '';
        // 一个 data 里带换行时（引用块就是多行），Spring 按 SSE 规范拆成多行 data:，
        // 行间要用 \n 接回去；直接 += 会把整块引用挤成一行。
        const dataLines = [];
        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventName = line.substring(6).trim();
          } else if (line.startsWith('data:')) {
            dataLines.push(line.substring(5).trim());
          }
        }
        const data = dataLines.join('\n');

        if (data === '[DONE]') continue;

        if (eventName === 'refs') {
          refsText += data;
          refsRendered = true;
        } else {
          answerText += data;
        }

        bubble.textContent = answerText;
        if (refsRendered) {
          let refsDiv = bubble.querySelector('.refs-block');
          if (!refsDiv) {
            refsDiv = document.createElement('div');
            refsDiv.className = 'refs-block';
            bubble.appendChild(refsDiv);
          }
          refsDiv.innerHTML = '<strong>📎 引用来源</strong><br>'
            + (refsText || '').replace(/\n/g, '<br>');
        }
        chatArea.scrollTop = chatArea.scrollHeight;
      }
    }

    finishStreamingBubble();
  } catch (e) {
    removeStreamingBubble();
    addMsg('ai', '❌ 请求失败：' + e.message + '\n\n请检查后端是否已启动（默认端口 8080）。');
  }
}

sendBtn.addEventListener('click', () => sendMessage(msgInput.value));

msgInput.addEventListener('keydown', e => {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault();
    sendMessage(msgInput.value);
  }
});

msgInput.addEventListener('input', () => {
  msgInput.style.height = 'auto';
  msgInput.style.height = Math.min(msgInput.scrollHeight, 140) + 'px';
});

// 快捷问题
document.querySelectorAll('.chip').forEach(chip => {
  chip.addEventListener('click', () => sendMessage(chip.dataset.q));
});

// ==================== 评估 ====================
const runEvalBtn = document.getElementById('runEvalBtn');
const evalBody = document.getElementById('evalBody');

runEvalBtn.addEventListener('click', async () => {
  runEvalBtn.disabled = true;
  runEvalBtn.textContent = '⏳ 评估运行中...';
  evalBody.innerHTML = '<div class="eval-placeholder">已提交后台评估，正在等待回执...</div>';

  try {
    const res = await fetch('/evaluation/run');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const submit = await res.json();
    if (!submit.taskId) throw new Error('未拿到 taskId');
    const report = await pollEvalStatus(submit.taskId);
    renderEval(report);
  } catch (e) {
    evalBody.innerHTML = '<div class="eval-placeholder" style="color:var(--danger)">❌ 评估失败：' + e.message + '</div>';
  } finally {
    runEvalBtn.disabled = false;
    runEvalBtn.textContent = '▶ 开始评估';
  }
});

// /evaluation/run 提交即返回，报告落在后台任务表里；全量 61 题实测约 394s，所以轮询到 10 分钟为止
async function pollEvalStatus(taskId) {
  for (let i = 0; i < 200; i++) {
    await new Promise(r => setTimeout(r, 3000));
    const res = await fetch('/evaluation/status/' + taskId);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const d = await res.json();
    if (d.state === 'DONE') return d.report;
    if (d.state === 'FAILED') throw new Error(d.error || '后台评估失败');
    const secs = Math.round((d.elapsed_ms || 0) / 1000);
    evalBody.innerHTML = '<div class="eval-placeholder">后台运行中… 已用 ' + secs
      + 's（全量 61 题实测约 394s，别关页面）</div>';
  }
  throw new Error('等待超时，任务仍在后台跑：稍后可再查 /evaluation/status/' + taskId);
}

function renderEval(d) {
  const total = d.total_count || 0;
  const pass = d.pass_count || 0;
  const rate = d.pass_rate || '0%';
  const dur = d.duration_ms || 0;
  const dims = d.dimensions || {};
  const results = d.detailed_results || [];

  // 核心分数
  let html = `
    <div class="score-card">
      <div class="score-box rate">
        <div class="score-value">${rate}</div>
        <div class="score-label">整体通过率</div>
      </div>
      <div class="score-box">
        <div class="score-value">${pass}/${total}</div>
        <div class="score-label">通过 / 总数</div>
      </div>
      <div class="score-box">
        <div class="score-value">${(dur/1000).toFixed(1)}s</div>
        <div class="score-label">总耗时</div>
      </div>
    </div>
  `;

  // 只有四道门禁算「得分」。诊断项是命中越少越好，画成百分比进度条会被读反（0/61 显示成 0% 像考了零分）
  const dimLabels = { '答案准确性': '准确性', '引用正确性': '引用正确性', '幻觉抑制': '幻觉抑制', '合规性': '合规性' };
  html += '<div class="dimensions"><h3>📊 四维度得分（门禁，计入通过率）</h3>';
  Object.entries(dims).filter(([k]) => dimLabels[k]).forEach(([k, v]) => {
    const m = String(v).match(/(\d+)\s*\/\s*(\d+)/);
    const pct = m ? Math.round(parseInt(m[1]) / parseInt(m[2]) * 100) : 0;
    html += `
      <div class="dim-row">
        <span class="dim-name">${dimLabels[k]}</span>
        <div class="dim-bar"><div class="dim-fill" style="width:${pct}%"></div></div>
        <span class="dim-text">${pct}%（${v}）</span>
      </div>
    `;
  });
  const diagnostics = Object.entries(dims).filter(([k]) => !dimLabels[k]);
  if (diagnostics.length) {
    html += '<h3>🔎 诊断项（不计入通过率，命中越少越好）</h3>';
    diagnostics.forEach(([k, v]) => {
      html += `
        <div class="dim-row">
          <span class="dim-name">${k}</span>
          <span class="dim-text">${v}</span>
        </div>
      `;
    });
  }
  html += '</div>';

  // 摘要
  if (d.summary) {
    html += `<div class="diag-detail" style="margin-bottom:20px">${d.summary}</div>`;
  }

  // 详细表格
  html += `
    <h3 style="margin-bottom:14px;color:var(--primary-dark)">📋 逐题结果</h3>
    <div style="overflow-x:auto">
      <table class="detail-table">
        <thead>
          <tr>
            <th style="width:40px">#</th>
            <th>问题</th>
            <th style="width:80px">分类</th>
            <th style="width:60px">准确性</th>
            <th style="width:60px">引用</th>
            <th style="width:60px">幻觉</th>
            <th style="width:60px">合规</th>
            <th style="width:60px">总评</th>
          </tr>
        </thead>
        <tbody>
  `;
  results.forEach((r, i) => {
    html += `
      <tr>
        <td>${i + 1}</td>
        <td title="${r.question}">${r.question.length > 36 ? r.question.slice(0, 36) + '...' : r.question}</td>
        <td>${r.category || '-'}</td>
        <td>${tag(r.accuracy_pass)}</td>
        <td>${tag(r.reference_pass)}</td>
        <td>${tag(r.hallucination_pass)}</td>
        <td>${tag(r.compliance_pass)}</td>
        <td>${tag(r.all_pass)}</td>
      </tr>
    `;
  });
  html += '</tbody></table></div>';

  evalBody.innerHTML = html;
}

function tag(pass) {
  return pass ? '<span class="pass-tag">✓ 通过</span>' : '<span class="fail-tag">✗ 未过</span>';
}

// ==================== 诊断 ====================
const refreshDiagBtn = document.getElementById('refreshDiagBtn');
const diagBody = document.getElementById('diagBody');

refreshDiagBtn.addEventListener('click', async () => {
  refreshDiagBtn.disabled = true;
  diagBody.innerHTML = '<div class="diag-placeholder">正在获取诊断信息...</div>';

  try {
    const res = await fetch('/rag/diag');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    renderDiag(data);
  } catch (e) {
    diagBody.innerHTML = '<div class="diag-placeholder" style="color:var(--danger)">❌ 获取失败：' + e.message + '</div>';
  } finally {
    refreshDiagBtn.disabled = false;
  }
});

function renderDiag(d) {
  const ri = d.rag_initializer || {};
  const ocr = (ri.ocr || {}).ocr_ready;
  const so = ri.stored_overview || {};
  const boot = typeof ri.last_boot_ingest === 'object' ? ri.last_boot_ingest : null;
  const scanned = ri.scanned_file_count ?? '-';
  const stored = so.files_in_store;
  const chunks = so.chunks_in_store;
  const bm25 = d.bm25_index || {};

  // files[] 是逐份体检（磁盘文件 + 库内指纹），per_file 是按 source 聚合的库内清单，只在前者空着时兜底。
  // 键名跟着 RagInitializer 走：包名重命名时 pdfs/scanned_pdf_count 已改成 files/scanned_file_count，
  // 前端没跟上，所以这两处一直在走兜底分支。
  const list = (ri.files && ri.files.length) ? ri.files : (so.per_file || []);
  let recent = list.map(p => {
    const name = p.name || p.source;
    const got = p.stored_chunks ?? p.chunks ?? '?';
    const want = p.stored_total_chunks ?? p.total_chunks;
    const flags = [
      (p.complete === false || p.incomplete) ? '块数不全' : '',
      p.stale_extract_version ? '旧抽取版本' : '',
      (p.file_missing_on_disk || p.in_store === false) ? '不在库/磁盘缺失' : '',
    ].filter(Boolean).join('、');
    return `  · ${name} — ${got}${want ? '/' + want : ''} chunks${flags ? ' ⚠ ' + flags : ''}`;
  }).join('\n') || '  （暂无）';
  if (ri.files_truncated || so.per_file_truncated) recent += '\n  …（列表已按上限截断）';

  const dimCards = [
    { title: '向量库类型', val: d.vector_store_type ? d.vector_store_type.split('.').pop() : '-', cls: 'info' },
    { title: '向量库就绪', val: d.vector_store_is_null ? '❌ 未就绪' : '✅ 就绪', cls: d.vector_store_is_null ? 'err' : 'ok' },
    { title: 'PgVector 扩展', val: d.pg_vector_initializer_ready === undefined ? '-' : (d.pg_vector_initializer_ready ? '✅ 已安装' : '❌ 缺失'), cls: d.pg_vector_initializer_ready ? 'ok' : 'err' },
    { title: 'OCR 引擎', val: ocr === undefined ? '-' : (ocr ? '✅ Tesseract OK' : '❌ 不可用'), cls: ocr ? 'ok' : 'err' },
    { title: 'PDF 总数（磁盘）', val: scanned, cls: 'info' },
    { title: '已入库（份 / 块）', val: stored == null ? '-' : `${stored} 份 / ${chunks} 块`,
      cls: boot && boot.failed > 0 ? 'err' : 'ok' },
    { title: 'BM25 关键词索引', val: bm25.available ? `${bm25.docs} docs / ${bm25.sources} 份` : '❌ 不可用',
      cls: bm25.available ? 'ok' : 'err' },
  ];

  let html = '<div class="diag-grid">';
  dimCards.forEach(c => {
    html += `<div class="diag-card"><h4>${c.title}</h4><div class="val ${c.cls}">${c.val}</div></div>`;
  });
  html += '</div>';

  html += '<div class="diag-card" style="margin-top:16px"><h4>📚 库内文件逐份体检</h4><div class="diag-detail">' + recent + '</div></div>';

  diagBody.innerHTML = html;
}