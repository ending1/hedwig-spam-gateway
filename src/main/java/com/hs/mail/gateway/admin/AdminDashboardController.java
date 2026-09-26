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
                "    <thead><tr><th>ID</th><th>유형</th><th>패턴</th><th>가중치</th><th>적중(스팸판정)</th><th>상태</th><th>설명</th><th></th></tr></thead>\n" +
                "    <tbody></tbody>\n" +
                "  </table>\n" +
                "  <div id=\"advicePanel\" style=\"display:none;position:fixed;inset:0;background:rgba(0,0,0,0.45);z-index:1000;align-items:center;justify-content:center\"><div id=\"adviceBox\" style=\"background:white;padding:1.5rem;border-radius:10px;max-width:640px;width:90%;max-height:80vh;overflow:auto;box-shadow:0 10px 30px rgba(0,0,0,0.3)\"></div></div>\n" +
                "</section>\n" +
                "<section>\n" +
                "  <h2>사용자 스팸 신고 (LLM 판정)</h2>\n" +
                "  <p style=\"font-size:0.85rem;color:#555\">신고 → LLM이 판정/의견 → 확신도 높은 스팸은 RAG 사례로 자동 편입 → 룰 추가는 여기서 승인해야 반영됩니다.</p>\n" +
                "  <table id=\"reportTable\">\n" +
                "    <thead><tr><th>ID</th><th>신고자</th><th>발신 도메인</th><th>제목 / 본문 일부</th><th>LLM 판정</th><th>의견</th><th>제안 룰</th><th>상태</th><th>RAG</th><th></th></tr></thead>\n" +
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
                "  ruleById = {}; rows.forEach(r => { ruleById[r.id] = r; });\n" +
                "  const stats = {};\n" +
                "  try { const sr = await adminFetch('/admin/spam-rules/stats'); if (sr.ok) (await sr.json()).forEach(x => { stats[x.ruleId] = x; }); } catch (e) {}\n" +
                "  const tbody = document.querySelector('#ruleTable tbody');\n" +
                "  tbody.innerHTML = rows.map(r => `<tr>\n" +
                "    <td>${r.id}</td><td>${r.ruleType}</td><td>${escapeHtml(r.pattern)}</td><td>${r.weight}</td>\n" +
                "    <td>${stats[r.id] ? stats[r.id].hits + ' (' + stats[r.id].spamHits + ')' : '0'}</td>\n" +
                "    <td>${r.enabled ? '활성' : '비활성'}</td><td>${r.reason || ''}</td>\n" +
                "    <td>\n" +
                "      <button onclick=\"toggleRule(${r.id}, '${r.ruleType}', '${encodeAttr(r.pattern)}', ${r.weight}, ${!r.enabled}, '${encodeAttr(r.reason || '')}')\">${r.enabled ? '비활성화' : '활성화'}</button>\n" +
                "      <button onclick=\"analyzeRule(${r.id})\">분석</button>\n" +
                "      <button class=\"danger\" onclick=\"removeRule(${r.id})\">삭제</button>\n" +
                "    </td>\n" +
                "  </tr>`).join('');\n" +
                "}\n" +
                "function escapeHtml(s) { const d = document.createElement('div'); d.innerText = s == null ? '' : s; return d.innerHTML; }\n" +
                "function encodeAttr(s) { return (s == null ? '' : s).replace(/'/g, \"\\\\'\"); }\n" +
                "let ruleById = {};\n" +
                "/** 룰 적중 샘플을 보여주고 LLM 조언을 받아 패널에 표시한다. 적용은 관리자가 버튼으로 직접 한다. */\n" +
                "async function analyzeRule(id) {\n" +
                "  const overlay = document.getElementById('advicePanel'); const panel = document.getElementById('adviceBox');\n" +
                "  const r = ruleById[id];\n" +
                "  overlay.style.display = 'flex'; overlay.onclick = (e) => { if (e.target === overlay) overlay.style.display = 'none'; };\n" +
                "  panel.innerHTML = '분석 중... (LLM 호출, 수 초 걸릴 수 있음)';\n" +
                "  let html = `<b>룰 #${id}</b> ${escapeHtml(r.pattern)} (현재 가중치 ${r.weight})<br>`;\n" +
                "  const sres = await adminFetch(`/admin/spam-rules/${id}/samples`);\n" +
                "  if (sres.ok) {\n" +
                "    const samples = await sres.json();\n" +
                "    html += '<details><summary>적중 샘플 ' + samples.length + '건(마스킹됨)</summary><ul>' + samples.map(s => `<li>[${s.spamVerdict ? '스팸' : '정상'}] ${escapeHtml(s.fromDomain)} | ${escapeHtml(s.subject)} | ${escapeHtml(s.snippet)}</li>`).join('') + '</ul></details>';\n" +
                "  }\n" +
                "  const ares = await adminFetch(`/admin/spam-rules/${id}/advice`, { method: 'POST' });\n" +
                "  const body = await ares.json();\n" +
                "  if (!ares.ok) { panel.innerHTML = html + `<p style=\"color:#dc2626\">${escapeHtml(body.error || '조언을 받지 못했습니다')}</p><button onclick=\"document.getElementById('advicePanel').style.display='none'\">닫기</button>`; return; }\n" +
                "  const actionKo = { KEEP: '유지', RAISE: '상향', LOWER: '하향', DISABLE: '비활성화' }[body.action] || body.action;\n" +
                "  html += `<p><b>LLM 조언(참고용)</b>: ${actionKo}` + (body.recommendedWeight != null ? ` → 권장 가중치 ${body.recommendedWeight}` : '') + ` (신뢰도 ${body.confidence}, 적중 ${body.hits}건)<br>${escapeHtml(body.rationale)}</p>`;\n" +
                "  html += '<button id=\"applyAdvice\">권장 가중치 적용</button> <button id=\"closeAdvice\" style=\"background:#6b7280\">닫기</button>';\n" +
                "  panel.innerHTML = html;\n" +
                "  document.getElementById('closeAdvice').onclick = () => { overlay.style.display = 'none'; };\n" +
                "  document.getElementById('applyAdvice').onclick = async () => {\n" +
                "    const w = body.action === 'DISABLE' ? r.weight : body.recommendedWeight;\n" +
                "    if (w == null) { alert('권장 가중치가 없습니다'); return; }\n" +
                "    if (!confirm(`룰 #${id}를 ` + (body.action === 'DISABLE' ? '비활성화' : `가중치 ${w}로 변경`) + '합니다. 진행할까요?')) return;\n" +
                "    await toggleRule(id, r.ruleType, r.pattern, w, body.action === 'DISABLE' ? false : r.enabled, r.reason || '');\n" +
                "    overlay.style.display = 'none';\n" +
                "  };\n" +
                "}\n" +
                "async function loadReports() {\n" +
                "  const tbody = document.querySelector('#reportTable tbody');\n" +
                "  const res = await adminFetch('/admin/spam-reports');\n" +
                "  if (!res.ok) { tbody.innerHTML = ''; return; }\n" +
                "  const rows = await res.json();\n" +
                "  window.reportById = {}; rows.forEach(r => { window.reportById[r.id] = r; });\n" +
                "  const vk = { SPAM: '스팸', HAM: '정상', UNSURE: '불확실' };\n" +
                "  tbody.innerHTML = rows.map(r => `<tr>\n" +
                "    <td>${r.id}</td><td>${escapeHtml(r.reporter)}</td><td>${escapeHtml(r.fromDomain)}</td>\n" +
                "    <td><b>${escapeHtml(r.subject)}</b><br><span style=\"color:#555\">${escapeHtml((r.snippet || '').substring(0, 120))}</span></td>\n" +
                "    <td>${r.status === 'PENDING' ? '분석 중' : (vk[r.verdict] || '-') + ' (' + Math.round((r.score || 0) * 100) + '%)'}</td>\n" +
                "    <td>${escapeHtml(r.reason || '')}</td>\n" +
                "    <td>${r.suggestedPattern ? '<code>' + escapeHtml(r.suggestedPattern) + '</code> (' + r.suggestedWeight + ')' : '-'}</td>\n" +
                "    <td>${{ PENDING: '분석 중', ANALYZED: '검토 대기', RULE_APPROVED: '룰 추가됨', DISMISSED: '기각' }[r.status] || r.status}</td>\n" +
                "    <td>${r.ragAdded ? '사용 중' : '-'}</td>\n" +
                "    <td>\n" +
                "      <button data-act=\"approve\" data-id=\"${r.id}\">룰 추가</button>\n" +
                "      <button data-act=\"rag\" data-id=\"${r.id}\">${r.ragAdded ? 'RAG 제외' : 'RAG 추가'}</button>\n" +
                "      <button class=\"danger\" data-act=\"dismiss\" data-id=\"${r.id}\">기각</button>\n" +
                "    </td>\n" +
                "  </tr>`).join('');\n" +
                "}\n" +
                "document.querySelector('#reportTable tbody').addEventListener('click', async (e) => {\n" +
                "  const btn = e.target.closest('button[data-act]'); if (!btn) return;\n" +
                "  const id = btn.dataset.id; const r = window.reportById[id];\n" +
                "  if (btn.dataset.act === 'approve') {\n" +
                "    const pattern = prompt('추가할 키워드 룰(정규식). LLM 제안값을 고치거나 직접 입력하세요.', r.suggestedPattern || '');\n" +
                "    if (!pattern) return;\n" +
                "    const w = parseFloat(prompt('가중치(임계치 5 기준, 단독으로 스팸 처리하려면 5 이상)', r.suggestedWeight > 0 ? r.suggestedWeight : 2));\n" +
                "    if (isNaN(w)) return;\n" +
                "    const res = await adminFetch(`/admin/spam-reports/${id}/approve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ pattern, weight: w }) });\n" +
                "    if (!res.ok) { const b = await res.json(); alert(b.error || '룰을 추가하지 못했습니다'); return; }\n" +
                "    loadRules();\n" +
                "  } else if (btn.dataset.act === 'rag') {\n" +
                "    await adminFetch(`/admin/spam-reports/${id}/rag`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ on: !r.ragAdded }) });\n" +
                "  } else if (btn.dataset.act === 'dismiss') {\n" +
                "    if (!confirm('이 신고를 기각합니다(RAG 사례에서도 제외됩니다). 진행할까요?')) return;\n" +
                "    await adminFetch(`/admin/spam-reports/${id}/dismiss`, { method: 'POST' });\n" +
                "  }\n" +
                "  loadReports();\n" +
                "});\n" +
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
                "  showAuth('', false); loadList(); loadRules(); loadReports();\n" +
                "});\n" +
                "document.getElementById('keyClear').addEventListener('click', () => {\n" +
                "  setKey(''); document.getElementById('adminKey').value = '';\n" +
                "  showAuth('키를 지웠습니다', false); loadList(); loadRules(); loadReports();\n" +
                "});\n" +
                "document.getElementById('adminKey').value = getKey();\n" +
                "loadStats(); loadList(); loadRules(); loadReports(); loadReports();\n" +
                "setInterval(loadStats, 5000);\n" +
                "</script>\n" +
                "</body>\n" +
                "</html>\n";
    }
}
