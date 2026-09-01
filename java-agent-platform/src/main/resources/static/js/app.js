(function () {
  'use strict';

  const chatArea = document.getElementById('chatArea');
  const inputBox = document.getElementById('inputBox');
  const btnSend = document.getElementById('btnSend');
  const btnClear = document.getElementById('btnClear');

  // 会话 ID 持久化在 localStorage，刷新页面可继续多轮对话
  const SESSION_KEY = 'smartservice_session_id';
  let sessionId = localStorage.getItem(SESSION_KEY) || 'sess_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  localStorage.setItem(SESSION_KEY, sessionId);

  let streaming = false;

  // ========== 渲染 ==========

  function appendMessage(role, text) {
    const wrap = document.createElement('div');
    wrap.className = 'msg ' + (role === 'user' ? 'user' : 'ai');

    const avatar = document.createElement('div');
    avatar.className = 'avatar';
    avatar.textContent = role === 'user' ? '我' : 'AI';

    const bubble = document.createElement('div');
    bubble.className = 'bubble';
    bubble.textContent = text;

    wrap.appendChild(avatar);
    wrap.appendChild(bubble);
    chatArea.appendChild(wrap);
    chatArea.scrollTop = chatArea.scrollHeight;
    return bubble;
  }

  function showIntentTag(intent) {
    const tag = document.createElement('div');
    tag.className = 'intent-tag';
    tag.textContent = '意图路由 → ' + intent;
    chatArea.appendChild(tag);
    chatArea.scrollTop = chatArea.scrollHeight;
  }

  function removeWelcome() {
    const welcome = chatArea.querySelector('.welcome');
    if (welcome) welcome.remove();
  }

  // ========== SSE 流式请求 ==========

  async function sendMessage(text) {
    if (streaming) return;
    streaming = true;
    btnSend.disabled = true;
    inputBox.value = '';

    removeWelcome();
    appendMessage('user', text);

    const aiBubble = appendMessage('ai', '');
    aiBubble.appendChild(document.createElement('span')).className = 'cursor';

    try {
      const resp = await fetch('/api/agent/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ userId: 'web_user', sessionId, message: text })
      });

      if (!resp.ok) throw new Error('HTTP ' + resp.status);
      if (!resp.body) throw new Error('无响应流');

      const reader = resp.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        let idx;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const chunk = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          handleEvent(chunk, aiBubble);
        }
      }
    } catch (err) {
      aiBubble.textContent = '请求失败：' + err.message;
    } finally {
      streaming = false;
      btnSend.disabled = false;
      inputBox.focus();
    }
  }

  function handleEvent(chunk, bubble) {
    const dataLine = chunk.split('\n').find(l => l.startsWith('data:'));
    if (!dataLine) return;
    const data = dataLine.slice(5).trim();
    if (!data) return;

    let payload = null;
    try { payload = JSON.parse(data); } catch (e) { /* 纯文本 token */ }

    // done 事件：payload 是对象且 done=true，携带意图信息
    if (payload && typeof payload === 'object' && payload.done) {
      const cursor = bubble.querySelector('.cursor');
      if (cursor) cursor.remove();
      if (payload.intent) showIntentTag(payload.intent);
      return;
    }

    // 流式 token：payload 为 null 时是后端推送的纯文本片段（如"北京"/"今天"/"天气"）
    if (payload !== null) return;        // 其他结构化消息（暂未使用）忽略
    if (data === '[DONE]') return;

    bubble.textContent += data;
    chatArea.scrollTop = chatArea.scrollHeight;
  }

  // ========== 事件绑定 ==========

  btnSend.addEventListener('click', () => {
    const text = inputBox.value.trim();
    if (text) sendMessage(text);
  });

  inputBox.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.isComposing) {
      const text = inputBox.value.trim();
      if (text) sendMessage(text);
    }
  });

  btnClear.addEventListener('click', async () => {
    try {
      await fetch('/api/agent/sessions/' + sessionId, { method: 'DELETE' });
    } catch (e) { /* 忽略清空失败 */ }
    chatArea.innerHTML = '';
    location.reload();
  });

  document.querySelectorAll('.chip').forEach(chip => {
    chip.addEventListener('click', () => sendMessage(chip.dataset.msg));
  });

  inputBox.focus();
})();
