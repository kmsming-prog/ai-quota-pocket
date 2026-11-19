/* Reads already-authorized usage-page DOM only. No network or cookie access. */
(() => {
  function exactReset(text) {
    if(typeof text!=='string')return null;
    const iso=text.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,3})?)?(Z|([+-])(\d{2}):?(\d{2}))$/);
    const zh=text.match(/(\d{4})年\s*(\d{1,2})月\s*(\d{1,2})日[\s\S]*?(上午|下午|晚上|中午|凌晨|早上)?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?[\s\S]*?\[GMT([+-]\d{1,2})(?::(\d{2}))?\]/);
    let y,m,d,h,minute,second,offset;
    if(iso){[y,m,d,h,minute,second]=iso.slice(1,7).map(Number);second=second||0;offset=iso[7]==='Z'?0:(iso[8]==='-'?-1:1)*(Number(iso[9])*60+Number(iso[10]));}
    else if(zh){y=Number(zh[1]);m=Number(zh[2]);d=Number(zh[3]);h=Number(zh[5]);minute=Number(zh[6]);second=Number(zh[7]||0);if(zh[4]){if(h<1||h>12)return null;h%=12;if(/下午|晚上|中午/.test(zh[4]))h+=12;}offset=Number(zh[8])*60+Math.sign(Number(zh[8]))*Number(zh[9]||0);}
    else return null;
    if(h>23||minute>59||second>59||Math.abs(offset)>840)return null;
    const utc=Date.UTC(y,m-1,d,h,minute,second),date=new Date(utc);
    if(date.getUTCFullYear()!==y||date.getUTCMonth()!==m-1||date.getUTCDate()!==d)return null;
    return utc-offset*60000;
  }
  function resetMeta(row, fallback) {
    if(!row)return {resetText:fallback,resetAt:null};
    for(const element of [row,...row.querySelectorAll('time[datetime],[title]')])for(const name of ['datetime','title']){
      const value=element.getAttribute(name);const resetAt=exactReset(value);if(resetAt!==null)return {resetText:'重置時間：'+value,resetAt};
    }
    return {resetText:fallback,resetAt:null};
  }
  function percent(text) {
    const used = [/(?:已使用|已用|已消耗|已使用了|\bused)\s*(\d+(?:\.\d+)?)\s*[%％]/i, /(\d+(?:\.\d+)?)\s*[%％]\s*(?:used|已用|已使用)/i];
    const left = [/(?:剩餘|剩余|餘額|remaining)\s*(\d+(?:\.\d+)?)\s*[%％]/i, /(\d+(?:\.\d+)?)\s*[%％]\s*(?:left|remaining|available|剩餘|剩余)/i];
    for (const [patterns, invert] of [[used, true], [left, false]]) for (const pattern of patterns) {
      const match = text.match(pattern); if (!match) continue;
      const n = Number(match[1]); if (n < 0 || n > 100) return null;
      return Math.round((invert ? 100 - n : n) * 10) / 10;
    }
    return null;
  }
  function period(label) {
    if (/^(?:this week|weekly(?: usage)? limits?|all models|每[週周](?:用量)?(?:上限|限制)|本[週周])$/i.test(label.trim())) return 'weekly';
    if (/^(?:current session|current usage|5\s*[-–]?\s*h(?:(?:ou)?rs?)?\s+(?:usage )?limit|5\s*小[時时]\s*(?:用量)?(?:上限|限制)|目前用量|目前的工作階段|當前用量|當期|目前工作階段|当前会话)$/i.test(label.trim())) return 'session';
    return null;
  }
  function normalize(records) {
    const result = [];
    for (const r of records) {
      const id = period(r.label), remainingPct = percent(r.text);
      if (!id || remainingPct === null || result.some(w=>w.id===id)) continue;
      result.push({ id, label:id==='session'?'當期':'本週', remainingPct, resetText:(r.resetText || '').slice(0,160),resetAt:Number.isSafeInteger(r.resetAt)?r.resetAt:null });
    }
    return result;
  }
  function extract(doc = document, href = location.href, now = Date.now()) {
    const url = new URL(href), records = []; let provider = '', sourceUpdated = '';
    const text = e => (e?.innerText || e?.textContent || '').trim();
    const reset = value => value.split(/\n/).map(s=>s.trim()).find(s=>/(?:resets?|refresh|starts with|重設|重置|重设)/i.test(s)) || '';
    const resetFromRow = row => {
      const value = text(row);
      const fallback = reset(value) || row.querySelector('time')?.textContent?.trim() || '';
      return resetMeta(row, fallback);
    };
    const context = (e, test) => { for(let n=e, i=0; n && i<8; n=n.parentElement,i++) { const t=text(n); if(t.length>1400) break; if(test(t)) return n; } return null; };
    const visible = e => e.getClientRects().length>0;
    if (url.hostname === 'claude.ai' && (/\/settings\/usage/.test(url.pathname) || /settings\/usage/.test(url.hash))) {
      provider = 'claude';
      for(const e of doc.querySelectorAll('[role="meter"],[role="progressbar"]')) {
        if(!visible(e)) continue;
        const label = e.getAttribute('aria-label') || (e.getAttribute('aria-labelledby')||'').split(/\s+/).map(id=>text(doc.getElementById(id))).join(' ');
        if(!period(label)) continue;
        const row = context(e,t=>t.includes(label) && percent(t)!==null);
        const aria = e.getAttribute('aria-valuetext') || '';
        const around = row ? text(row) : '';
        records.push({label,text:percent(aria) === null ? around : aria,...(row ? resetFromRow(row) : {})});
      }
    } else if(url.hostname === 'chatgpt.com') {
      provider='chatgpt';
    } else throw Error('請先開啟 Codex 或 Claude 的官方用量頁。');
    if (records.length < 2) {
      for (const element of doc.querySelectorAll('h2,h3,h4,h5,div,span,p,label,dt')) {
        const candidate = element.textContent.trim();
        const window = candidate.length < 50 ? period(candidate) : null;
        if (!window || !visible(element) || records.some(record => period(record.label) === window && percent(record.text) !== null)) continue;
        const row = context(element, value => {
          const labelled = value.split(/\n+/).map(period).filter(Boolean);
          const percentages = value.match(/\d+(?:\.\d+)?\s*[%％]/g) || [];
          return labelled.includes(window) && labelled.every(id => id === window)
            && percentages.length === 1 && percent(value) !== null;
        });
        if (!row) continue;
        const content = text(row);
        records.push({ label: candidate, text: content, ...resetFromRow(row) });
      }
    }
    const windows=normalize(records);
    if(!windows.length) throw Error('沒有找到有明確「已用／剩餘」標籤的百分比。請開啟完整用量頁。');
    return {provider,source:'extension',observedAt:now,sourceUpdated,windows};
  }
  globalThis.QuotaReader = {extract, percent, normalize,exactReset};
})();
