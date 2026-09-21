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

// ==================== 聊天 ====================
const chatArea = document.getElementById('chatArea');
const msgInput = document.getElementById('msgInput');
const sendBtn = document.getElementById('sendBtn');

function splitAnswer(raw) {
  // 后端返回格式：答案文本 + "\n\n---\n【引用(参考)来源】\n1.xxx\n2.xxx"
  const refIdx = raw.indexOf('【引用(参考)来源】');
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

function addThinking() {
  const welcome = chatArea.querySelector('.chat-welcome');
  if (welcome) welcome.remove();

  const wrap = document.createElement('div');
  wrap.className = 'msg ai';
  wrap.id = 'thinkingMsg';
  wrap.innerHTML = `
    <div class="msg-avatar">🩺</div>
    <div class="msg-bubble"><div class="thinking"><span></span><span></span><span></span></div></div>
  `;
  chatArea.appendChild(wrap);
  chatArea.scrollTop = chatArea.scrollHeight;
}

function removeThinking() {
  const t = document.getElementById('thinkingMsg');
  if (t) t.remove();
}

async function sendMessage(text) {
  if (!text || !text.trim()) return;

  addMsg('user', text);
  sendBtn.disabled = true;
  addThinking();

  try {
    const res = await fetch('/chat?message=' + encodeURIComponent(text));
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const body = await res.text();
    removeThinking();
    addMsg('ai', body);
  } catch (e) {
    removeThinking();
    addMsg('ai', '❌ 请求失败：' + e.message + '\n\n请检查后端是否已启动（默认端口 8080）。');
  } finally {
    sendBtn.disabled = false;
    msgInput.value = '';
    msgInput.style.height = 'auto';
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
  evalBody.innerHTML = '<div class="eval-placeholder">正在运行 10 个测试用例，大约需要 1~3 分钟...</div>';

  try {
    const res = await fetch('/evaluation/run');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    renderEval(data);
  } catch (e) {
    evalBody.innerHTML = '<div class="eval-placeholder" style="color:var(--danger)">❌ 评估失败：' + e.message + '</div>';
  } finally {
    runEvalBtn.disabled = false;
    runEvalBtn.textContent = '▶ 开始评估';
  }
});

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

  // 维度进度条
  const dimLabels = { '答案准确性': '准确性', '引用正确性': '引用正确性', '幻觉抑制': '幻觉抑制', '合规性': '合规性' };
  html += '<div class="dimensions"><h3>📊 四维度得分</h3>';
  Object.entries(dims).forEach(([k, v]) => {
    const m = String(v).match(/(\d+)\s*\/\s*(\d+)/);
    const pct = m ? Math.round(parseInt(m[1]) / parseInt(m[2]) * 100) : 0;
    html += `
      <div class="dim-row">
        <span class="dim-name">${dimLabels[k] || k}</span>
        <div class="dim-bar"><div class="dim-fill" style="width:${pct}%"></div></div>
        <span class="dim-text">${pct}%（${v}）</span>
      </div>
    `;
  });
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
  const ocr = ri.ocr_ready;
  const totalPdf = ri.pdfs_total ?? '-';
  const okPdf = ri.pdfs_ingested_ok ?? '-';
  const pgReady = d.pg_vector_initializer_ready;

  const recent = (ri.recent_processed || []).map(p =>
    `  · ${p.name} — ${p.chunks} chunks (${p.source})`
  ).join('\n') || '  （暂无）';

  const dimCards = [
    { title: '向量库类型', val: d.vector_store_type ? d.vector_store_type.split('.').pop() : '-', cls: 'info' },
    { title: '向量库就绪', val: d.vector_store_is_null ? '❌ 未就绪' : '✅ 就绪', cls: d.vector_store_is_null ? 'err' : 'ok' },
    { title: 'PgVector 扩展', val: pgReady === undefined ? '-' : (pgReady ? '✅ 已安装' : '❌ 缺失'), cls: pgReady ? 'ok' : 'err' },
    { title: 'OCR 引擎', val: ocr === undefined ? '-' : (ocr ? '✅ Tesseract OK' : '❌ 不可用'), cls: ocr ? 'ok' : 'err' },
    { title: 'PDF 总数', val: totalPdf, cls: 'info' },
    { title: '已入库成功', val: okPdf, cls: okPdf === totalPdf ? 'ok' : 'info' },
  ];

  let html = '<div class="diag-grid">';
  dimCards.forEach(c => {
    html += `<div class="diag-card"><h4>${c.title}</h4><div class="val ${c.cls}">${c.val}</div></div>`;
  });
  html += '</div>';

  html += '<div class="diag-card" style="margin-top:16px"><h4>📚 最近入库记录</h4><div class="diag-detail">' + recent + '</div></div>';

  diagBody.innerHTML = html;
}