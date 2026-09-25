package com.hs.mail.gateway.admin;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 통계 대시보드 - /actuator/gateway(카운터)와 /admin/mail-list(화이트/블랙리스트)를
 * 주기적으로 폴링해 보여주는 단일 HTML 페이지. 별도 프론트엔드 빌드 없이 순수 JS로 동작한다.
 */
@RestController
public class AdminDashboardController {

    @GetMapping(value = "/admin/dashboard", produces = MediaType.TEXT_HTML_VALUE)
    public String dashboard() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"ko\">\n" +
                "<head>\n" +
                "<meta charset=\"UTF-8\">\n" +
                "<title>Hedwig Spam Gateway - 관리자 대시보드</title>\n" +
                "<style>\n" +
                "  body { font-family: -apple-system, sans-serif; margin: 2rem; background: #f5f6f8; color: #222; }\n" +
                "  h1 { font-size: 1.4rem; }\n" +
                "  .cards { display: flex; flex-wrap: wrap; gap: 1rem; margin-bottom: 2rem; }\n" +
                "  .card { background: white; border-radius: 8px; padding: 1rem 1.5rem; box-shadow: 0 1px 3px rgba(0,0,0,0.1); min-width: 180px; }\n" +
                "  .card h3 { margin: 0 0 0.5rem; font-size: 0.85rem; color: #666; font-weight: 600; }\n" +
                "  .card .value { font-size: 1.6rem; font-weight: 700; }\n" +
                "  table { width: 100%; border-collapse: collapse; background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }\n" +
                "  th, td { padding: 0.5rem 0.8rem; text-align: left; border-bottom: 1px solid #eee; font-size: 0.9rem; }\n" +
                "  th { background: #fafafa; }\n" +
                "  form { display: flex; gap: 0.5rem; margin: 1rem 0; flex-wrap: wrap; align-items: center; }\n" +
                "  input, select { padding: 0.4rem; border: 1px solid #ccc; border-radius: 4px; }\n" +
                "  button { padding: 0.4rem 0.9rem; border: none; border-radius: 4px; background: #2563eb; color: white; cursor: pointer; }\n" +
                "  button.danger { background: #dc2626; }\n" +
                "  section { margin-bottom: 2rem; }\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<h1>Hedwig Spam Gateway 관리자 대시보드</h1>\n" +
                "<form id=\"keyForm\" style=\"background:white;padding:0.6rem 1rem;border-radius:8px;box-shadow:0 1px 3px rgba(0,0,0,0.1);margin:0 0 1.5rem\">\n" +
                "  <label for=\"adminKey\" style=\"font-size:0.85rem;color:#555\">관리자 키</label>\n" +
                "  <input id=\"adminKey\" type=\"password\" autocomplete=\"off\" placeholder=\"설정된 경우에만 입력\" size=\"28\">\n" +
                "  <button type=\"submit\">적용</button>\n" +
                "  <button type=\"button\" id=\"keyClear\" style=\"background:#6b7280\">지우기</button>\n" +
                "  <span id=\"authStatus\" style=\"font-size:0.85rem\"></span>\n" +
                "</form>\n" +
                "<section>\n" +
                "  <div class=\"cards\" id=\"cards\">불러오는 중...</div>\n" +
                "</section>\n" +
                "<section>\n" +
                "  <h2>화이트/블랙리스트</h2>\n" +
                "  <form id=\"addForm\">\n" +
                "    <select id=\"listType\"><option value=\"WHITE\">화이트리스트</option><option value=\"BLACK\">블랙리스트</option></select>\n" +
                "    <input id=\"pattern\" placeholder=\"user@domain.com 또는 @domain.com\" size=\"28\" required>\n" +
                "    <input id=\"recipient\" placeholder=\"수신자(비우면 전역)\" size=\"20\">\n" +
                "    <input id=\"reason\" placeholder=\"사유(선택)\" size=\"20\">\n" +
                "    <button type=\"submit\">추가</button>\n" +
                "  </form>\n" +
                "  <table id=\"listTable\">\n" +
                "    <thead><tr><th>유형</th><th>패턴</th><th>수신자</th><th>사유</th><th></th></tr></thead>\n" +
                "    <tbody></tbody>\n" +
                "  </table>\n" +
                "</section>\n" +
                "<section>\n" +
                "  <h2>룰기반 스팸 필터 - 룰 관리</h2>\n" +
                "  <p style=\"color:#666;font-size:0.85rem;margin-top:-0.5rem\">키워드(정규식)/브랜드 사칭/프리메일 도메인/URL 단축서비스/구조체크 가중치를 재빌드 없이 여기서 바로 추가·수정·삭제합니다.</p>\n" +
                "  <form id=\"ruleAddForm\">\n" +
                "    <select id=\"ruleType\">\n" +
                "      <option value=\"KEYWORD\">KEYWORD(정규식)</option>\n" +
                "      <option value=\"BRAND\">BRAND(브랜드명)</option>\n" +
                "      <option value=\"FREE_MAIL_DOMAIN\">FREE_MAIL_DOMAIN(도메인)</option>\n" +
                "      <option value=\"URL_SHORTENER\">URL_SHORTENER(도메인)</option>\n" +
                "      <option value=\"STRUCTURAL\">STRUCTURAL(식별자)</option>\n" +
                "    </select>\n" +
                "    <input id=\"rulePattern\" placeholder=\"패턴/도메인/브랜드명/식별자\" size=\"28\" required>\n" +
                "    <input id=\"ruleWeight\" type=\"number\" step=\"0.5\" placeholder=\"가중치\" value=\"2.0\" size=\"6\">\n" +
                "    <input id=\"ruleReason\" placeholder=\"설명(선택)\" size=\"20\">\n" +
                "    <button type=\"submit\">추가</button>\n" +
                "  </form>\n" +
                "  <table id=\"ruleTable\">\n" +
                "    <thead><tr><th>ID</th><th>유형</th><th>패턴</th><th>가중치</th><th>상태</th><th>설명</th><th></th></tr></thead>\n" +
                "    <tbody></tbody>\n" +
                "  </table>\n" +
                "</section>\n" +
                "<script>\n" +
                "const KEY_STORE = 'hedwigAdminKey';\n" +
                "function getKey() { try { return sessionStorage.getItem(KEY_STORE) || ''; } catch (e) { return ''; } }\n" +
                "function setKey(v) { try { if (v) sessionStorage.setItem(KEY_STORE, v); else sessionStorage.removeItem(KEY_STORE); } catch (e) {} }\n" +
                "function showAuth(msg, bad) {\n" +
                "  const el = document.getElementById('authStatus');\n" +
                "  el.textContent = msg; el.style.color = bad ? '#dc2626' : '#16a34a';\n" +
                "}\n" +
                "/** /admin/* 호출 공통 래퍼: 저장된 키를 X-Admin-Key로 붙이고, 401이면 안내를 띄운다. */\n" +
                "async function adminFetch(url, opts) {\n" +
                "  opts = opts || {};\n" +
                "  const headers = Object.assign({}, opts.headers || {});\n" +
                "  if (getKey()) headers['X-Admin-Key'] = getKey();\n" +
                "  const res = await fetch(url, Object.assign({}, opts, { headers }));\n" +
                "  if (res.status === 401) {\n" +
                "    showAuth(getKey() ? '키가 올바르지 않습니다' : '관리자 키가 필요합니다', true);\n" +
                "  } else if (res.ok && getKey()) {\n" +
                "    showAuth('키 적용됨', false);\n" +
                "  }\n" +
                "  return res;\n" +
                "}\n" +
                "async function loadStats() {\n" +
                "  const res = await fetch('/actuator/gateway');\n" +
                "  const data = await res.json();\n" +
                "  const cards = document.getElementById('cards');\n" +
                "  const items = [\n" +
                "    ['현재 커넥션', data.currentConnections],\n" +
                "    ['현재 밴 IP 수', data.currentBanCount],\n" +
                "    ['누적 밴 건수', data.totalBanEventCount],\n" +
                "    ['스팸 판정(LLM)', data.spamFilter ? data.spamFilter.spamCount : '-'],\n" +
                "    ['정상 판정(LLM)', data.spamFilter ? data.spamFilter.hamCount : '-'],\n" +
                "    ['RBL 차단', data.rbl ? data.rbl.blockedCount : '-'],\n" +
                "    ['그레이리스팅 DEFER', data.greylist ? data.greylist.deferredCount : '-'],\n" +
                "    ['그레이리스팅 ALLOW', data.greylist ? data.greylist.allowedCount : '-'],\n" +
                "    ['아웃바운드 큐', data.outbound ? data.outbound.queueCount : '-'],\n" +
                "    ['아웃바운드 실패', data.outbound ? data.outbound.failureCount : '-']\n" +
                "  ];\n" +
                "  cards.innerHTML = items.map(([label, value]) =>\n" +
                "    `<div class=\"card\"><h3>${label}</h3><div class=\"value\">${value}</div></div>`).join('');\n" +
                "}\n" +
                "async function loadList() {\n" +
                "  const res = await adminFetch('/admin/mail-list');\n" +
                "  if (!res.ok) { document.querySelector('#listTable tbody').innerHTML = ''; return; }\n" +
                "  const rows = await res.json();\n" +
                "  const tbody = document.querySelector('#listTable tbody');\n" +
                "  tbody.innerHTML = rows.map(r => `<tr>\n" +
                "    <td>${r.listType}</td><td>${r.pattern}</td><td>${r.recipient || '(전역)'}</td><td>${r.reason || ''}</td>\n" +
                "    <td><button class=\"danger\" onclick=\"removeEntry('${r.listType}','${r.pattern}','${r.recipient}')\">삭제</button></td>\n" +
                "  </tr>`).join('');\n" +
                "}\n" +
                "async function removeEntry(listType, pattern, recipient) {\n" +
                "  await adminFetch(`/admin/mail-list?listType=${encodeURIComponent(listType)}&pattern=${encodeURIComponent(pattern)}&recipient=${encodeURIComponent(recipient)}`, { method: 'DELETE' });\n" +
                "  loadList();\n" +
                "}\n" +
                "document.getElementById('addForm').addEventListener('submit', async (e) => {\n" +
                "  e.preventDefault();\n" +
                "  await adminFetch('/admin/mail-list', {\n" +
                "    method: 'POST', headers: { 'Content-Type': 'application/json' },\n" +
                "    body: JSON.stringify({\n" +
                "      listType: document.getElementById('listType').value,\n" +
                "      pattern: document.getElementById('pattern').value,\n" +
                "      recipient: document.getElementById('recipient').value,\n" +
                "      reason: document.getElementById('reason').value\n" +
                "    })\n" +
                "  });\n" +
                "  document.getElementById('pattern').value = '';\n" +
                "  document.getElementById('recipient').value = '';\n" +
                "  document.getElementById('reason').value = '';\n" +
                "  loadList();\n" +
                "});\n" +
                "async function loadRules() {\n" +
                "  const res = await adminFetch('/admin/spam-rules');\n" +
                "  if (!res.ok) { document.querySelector('#ruleTable tbody').innerHTML = ''; return; }\n" +
                "  const rows = await res.json();\n" +
                "  const tbody = document.querySelector('#ruleTable tbody');\n" +
                "  tbody.innerHTML = rows.map(r => `<tr>\n" +
                "    <td>${r.id}</td><td>${r.ruleType}</td><td>${escapeHtml(r.pattern)}</td><td>${r.weight}</td>\n" +
                "    <td>${r.enabled ? '활성' : '비활성'}</td><td>${r.reason || ''}</td>\n" +
                "    <td>\n" +
                "      <button onclick=\"toggleRule(${r.id}, '${r.ruleType}', '${encodeAttr(r.pattern)}', ${r.weight}, ${!r.enabled}, '${encodeAttr(r.reason || '')}')\">${r.enabled ? '비활성화' : '활성화'}</button>\n" +
                "      <button class=\"danger\" onclick=\"removeRule(${r.id})\">삭제</button>\n" +
                "    </td>\n" +
                "  </tr>`).join('');\n" +
                "}\n" +
                "function escapeHtml(s) { const d = document.createElement('div'); d.innerText = s == null ? '' : s; return d.innerHTML; }\n" +
                "function encodeAttr(s) { return (s == null ? '' : s).replace(/'/g, \"\\\\'\"); }\n" +
                "async function toggleRule(id, ruleType, pattern, weight, enabled, reason) {\n" +
                "  await adminFetch(`/admin/spam-rules/${id}`, {\n" +
                "    method: 'PUT', headers: { 'Content-Type': 'application/json' },\n" +
                "    body: JSON.stringify({ ruleType, pattern, weight, enabled, reason })\n" +
                "  });\n" +
                "  loadRules();\n" +
                "}\n" +
                "async function removeRule(id) {\n" +
                "  await adminFetch(`/admin/spam-rules/${id}`, { method: 'DELETE' });\n" +
                "  loadRules();\n" +
                "}\n" +
                "document.getElementById('ruleAddForm').addEventListener('submit', async (e) => {\n" +
                "  e.preventDefault();\n" +
                "  await adminFetch('/admin/spam-rules', {\n" +
                "    method: 'POST', headers: { 'Content-Type': 'application/json' },\n" +
                "    body: JSON.stringify({\n" +
                "      ruleType: document.getElementById('ruleType').value,\n" +
                "      pattern: document.getElementById('rulePattern').value,\n" +
                "      weight: parseFloat(document.getElementById('ruleWeight').value || '1.0'),\n" +
                "      reason: document.getElementById('ruleReason').value\n" +
                "    })\n" +
                "  });\n" +
                "  document.getElementById('rulePattern').value = '';\n" +
                "  document.getElementById('ruleReason').value = '';\n" +
                "  loadRules();\n" +
                "});\n" +
                "document.getElementById('keyForm').addEventListener('submit', (e) => {\n" +
                "  e.preventDefault();\n" +
                "  setKey(document.getElementById('adminKey').value.trim());\n" +
                "  showAuth('', false); loadList(); loadRules();\n" +
                "});\n" +
                "document.getElementById('keyClear').addEventListener('click', () => {\n" +
                "  setKey(''); document.getElementById('adminKey').value = '';\n" +
                "  showAuth('키를 지웠습니다', false); loadList(); loadRules();\n" +
                "});\n" +
                "document.getElementById('adminKey').value = getKey();\n" +
                "loadStats(); loadList(); loadRules();\n" +
                "setInterval(loadStats, 5000);\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>\n";
    }
}
