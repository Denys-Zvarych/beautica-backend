(function () {
  var SCHEME = 'BearerAuth';
  var LS_KEY = 'beautica_swagger_token';

  function authorize(token) {
    if (window.ui && token) {
      window.ui.preauthorizeApiKey(SCHEME, token);
    }
  }

  function injectBar() {
    if (document.getElementById('beautica-token-bar')) return;

    var bar = document.createElement('div');
    bar.id = 'beautica-token-bar';
    bar.style.cssText = 'background:#fffde7;padding:8px 16px;display:flex;align-items:center;gap:8px;font-family:sans-serif;font-size:13px;border-bottom:1px solid #ffe082;';

    var label = document.createElement('span');
    label.textContent = 'JWT:';

    var input = document.createElement('input');
    input.type = 'text';
    input.placeholder = 'Paste Bearer token here';
    input.style.cssText = 'flex:1;padding:4px 8px;border:1px solid #ccc;border-radius:3px;font-size:13px;';
    var saved = localStorage.getItem(LS_KEY);
    if (saved) input.value = saved;

    var btn = document.createElement('button');
    btn.textContent = 'Save & Authorize';
    btn.style.cssText = 'padding:4px 12px;background:#43a047;color:#fff;border:none;border-radius:3px;cursor:pointer;font-size:13px;';
    btn.onclick = function () {
      var token = input.value.trim();
      if (!token) return;
      localStorage.setItem(LS_KEY, token);
      authorize(token);
    };

    var clear = document.createElement('a');
    clear.textContent = 'Clear';
    clear.href = '#';
    clear.style.cssText = 'font-size:12px;color:#c62828;';
    clear.onclick = function (e) {
      e.preventDefault();
      localStorage.removeItem(LS_KEY);
      location.reload();
    };

    bar.appendChild(label);
    bar.appendChild(input);
    bar.appendChild(btn);
    bar.appendChild(clear);

    var topbar = document.querySelector('.swagger-ui .topbar');
    if (topbar && topbar.parentNode) {
      topbar.parentNode.insertBefore(bar, topbar.nextSibling);
    } else {
      document.body.insertBefore(bar, document.body.firstChild);
    }
  }

  function waitForUi(elapsed) {
    elapsed = elapsed || 0;
    if (elapsed > 10000) return;
    var container = document.querySelector('.swagger-ui .scheme-container');
    if (container) {
      var token = localStorage.getItem(LS_KEY);
      if (token) authorize(token);
      injectBar();
      return;
    }
    setTimeout(function () { waitForUi(elapsed + 200); }, 200);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { waitForUi(0); });
  } else {
    waitForUi(0);
  }
}());
