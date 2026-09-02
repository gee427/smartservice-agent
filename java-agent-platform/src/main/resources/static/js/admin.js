(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);

  const TOKEN_KEY = 'admin_token';
  const USER_KEY = 'admin_user';
  const INTENT_LABELS = {
    WEATHER: '天气查询', CALC: '数学计算', CHAT: '通用对话'
  };

  let loginMode = 'login'; // login | register

  // ---------- 认证 ----------

  function getToken() { return localStorage.getItem(TOKEN_KEY); }
  function getUser() { return localStorage.getItem(USER_KEY) || '—'; }

  function showLogin(message) {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    $('dashboardView').hidden = true;
    $('loginView').style.display = 'flex';
    if (message) {
      $('loginError').textContent = message;
      $('loginError').style.display = 'block';
    }
  }

  function showDashboard() {
    $('loginView').style.display = 'none';
    $('dashboardView').hidden = false;
    $('userBadge').textContent = getUser();
    loadAll();
  }

  /**
   * 统一 fetch：带 Bearer token；401 时回登录页
   */
  async function api(path, options = {}) {
    const headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
    const token = getToken();
    if (token) headers['Authorization'] = 'Bearer ' + token;

    const resp = await fetch(path, Object.assign({}, options, { headers }));
    let body;
    try { body = await resp.json(); } catch (e) { body = { code: -1, message: '响应解析失败' }; }

    if (resp.status === 401 || body.code === 40100) {
      showLogin('登录已过期，请重新登录');
      throw new Error(body.message || '未认证');
    }
    if (body.code !== 0) throw new Error(body.message);
    return body.data;
  }

  async function getJSON(url) { return api(url); }

  // ---------- 登录 / 注册 ----------

  function setLoginMode(mode) {
    loginMode = mode;
    $('loginTitle').textContent = mode === 'login' ? '登录' : '注册账号';
    $('loginBtn').textContent = mode === 'login' ? '登 录' : '注 册';
    $('switchMode').textContent = mode === 'login' ? '没有账号？立即注册' : '已有账号？返回登录';
    $('loginError').style.display = 'none';
  }

  $('loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const username = $('loginUser').value.trim();
    const password = $('loginPass').value;
    const errBox = $('loginError');
    errBox.style.display = 'none';

    try {
      const data = await api(loginMode === 'login' ? '/api/auth/login' : '/api/auth/register', {
        method: 'POST',
        body: JSON.stringify({ username, password })
      });
      localStorage.setItem(TOKEN_KEY, data.token);
      localStorage.setItem(USER_KEY, data.username);
      $('loginPass').value = '';
      showDashboard();
    } catch (err) {
      errBox.textContent = err.message;
      errBox.style.display = 'block';
    }
  });

  $('switchMode').addEventListener('click', (e) => {
    e.preventDefault();
    setLoginMode(loginMode === 'login' ? 'register' : 'login');
  });

  $('btnLogout').addEventListener('click', () => showLogin());

  // ---------- 视图切换（侧边导航） ----------

  const VIEW_TITLES = {
    overview: '运营总览',
    sessions: '会话管理',
    agents: 'Agent 状态'
  };
  let currentView = 'overview';

  function switchView(view) {
    if (!VIEW_TITLES[view]) view = 'overview';
    currentView = view;

    // 切换侧边栏高亮
    document.querySelectorAll('.nav-item[data-view]').forEach(a => {
      a.classList.toggle('active', a.dataset.view === view);
    });

    // 隐藏所有 section，仅显示当前
    ['overview', 'sessions', 'agents'].forEach(name => {
      const sec = $(name);
      if (sec) sec.hidden = (name !== view);
    });

    // 同步主标题
    const h1 = document.querySelector('.main-header h1');
    if (h1) h1.textContent = VIEW_TITLES[view];
  }

  document.querySelectorAll('.nav-item[data-view]').forEach(a => {
    a.addEventListener('click', (e) => {
      e.preventDefault();
      switchView(a.dataset.view);
    });
  });

  // ---------- 数据渲染 ----------

  function setStatus(el, up) {
    el.textContent = up ? 'UP' : 'DOWN';
    el.className = 'status-value ' + (up ? 'up' : 'down');
  }

  function fmtTime(ts) {
    if (!ts) return '-';
    const d = new Date(ts);
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getMonth() + 1}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  }

  function renderIntentBars(distribution) {
    const box = $('intentBars');
    if (!distribution || distribution.length === 0) {
      box.innerHTML = '<p class="empty">暂无数据</p>';
      return;
    }
    const max = Math.max(...distribution.map(d => d.count), 1);
    box.innerHTML = distribution.map(d => `
      <div class="intent-row">
        <span class="intent-name">${INTENT_LABELS[d.intent] || d.intent}</span>
        <div class="intent-bar-track">
          <div class="intent-bar-fill" style="width:${Math.round(d.count / max * 100)}%"></div>
        </div>
        <span class="intent-count">${d.count}</span>
      </div>`).join('');
  }

  function renderSessions(sessions) {
    const tbody = $('sessionBody');
    $('sessionCount').textContent = sessions.length;
    if (!sessions || sessions.length === 0) {
      tbody.innerHTML = '<tr><td colspan="6" class="empty">暂无会话</td></tr>';
      return;
    }
    tbody.innerHTML = sessions.map(s => `
      <tr>
        <td class="mono">${s.sessionId}</td>
        <td>${s.userId || 'anonymous'}</td>
        <td><span class="intent-chip">${INTENT_LABELS[s.intent] || s.intent || '-'}</span></td>
        <td>${s.messageCount ?? 0} 轮</td>
        <td>${fmtTime(s.lastActive)}</td>
        <td><button class="btn-del" data-sid="${s.sessionId}">删除</button></td>
      </tr>`).join('');
    tbody.querySelectorAll('.btn-del').forEach(btn => {
      btn.addEventListener('click', async () => {
        if (!confirm('确定删除会话 ' + btn.dataset.sid + ' ？')) return;
        await api('/api/admin/sessions/' + encodeURIComponent(btn.dataset.sid), { method: 'DELETE' });
        loadAll();
      });
    });
  }

  function renderAgents(data) {
    const grid = $('agentGrid');
    if (!data || !data.agents) {
      grid.innerHTML = '<p class="empty">暂无数据</p>';
      return;
    }
    const intentMap = data.intentMap || {};
    grid.innerHTML = data.agents.map(name => {
      const intent = Object.keys(intentMap).find(k => intentMap[k] === name);
      return `
        <div class="agent-card">
          <strong>${name}</strong>
          <span>${intent ? '意图: ' + (INTENT_LABELS[intent] || intent) : '注册就绪'}</span>
        </div>`;
    }).join('');
  }

  async function loadAll() {
    try {
      const health = await getJSON('/api/admin/health');
      setStatus($('stPlatform'), health.status === 'UP');
      setStatus($('stRedis'), health.redis === 'UP');
      setStatus($('stLlm'), health.llm === 'UP');
      $('healthBadge').textContent = health.llm === 'UP' ? '全部正常' : 'LLM 异常';
      $('healthBadge').className = 'badge ' + (health.llm === 'UP' ? 'badge-up' : 'badge-down');

      const metrics = await getJSON('/api/admin/metrics');
      $('mTotal').textContent = Math.round(metrics.totalRequests);
      $('mLatency').textContent = Math.round(metrics.avgLatencyMs) + ' ms';
      $('mActive').textContent = Math.round(metrics.activeSessions);
      $('mTokens').textContent = Math.round(metrics.totalTokens);
      renderIntentBars(metrics.intentDistribution);

      const sessions = await getJSON('/api/admin/sessions?limit=50');
      renderSessions(sessions);

      const agents = await getJSON('/api/admin/agents');
      renderAgents(agents);

      $('refreshTime').textContent = '更新于 ' + fmtTime(Date.now());
    } catch (err) {
      if (err.message === '未认证') return; // 已回登录页
      $('healthBadge').textContent = '加载失败';
      $('healthBadge').className = 'badge badge-down';
      $('refreshTime').textContent = err.message;
    }
  }

  $('btnRefresh').addEventListener('click', loadAll);
  setInterval(loadAll, 5000);

  // ---------- 启动 ----------
  setLoginMode('login');
  if (getToken()) {
    showDashboard();
  } else {
    $('loginView').style.display = 'flex';
  }
})();
