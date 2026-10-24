(function () {
  if (window.__xbdInstalled) return;
  window.__xbdInstalled = true;

  var byMedia = {};   // mediaId -> best mp4 URL
  var byTweet = {};   // tweetId -> best mp4 URL
  var API = /\/i\/api\/|\/\/api\.(x|twitter)\.com\//;

  // ---------- 1. Capture video_info.variants from X API responses ----------

  function bestMp4(variants) {
    var best = null;
    for (var i = 0; i < variants.length; i++) {
      var v = variants[i];
      if (v && v.content_type === 'video/mp4' && v.url) {
        if (!best || (v.bitrate || 0) > (best.bitrate || 0)) best = v;
      }
    }
    return best ? best.url : null;
  }

  function mediaIdFrom(s) {
    var m = /(?:amplify_video_thumb|ext_tw_video_thumb|amplify_video|ext_tw_video)\/(\d+)/.exec(s || '');
    return m ? m[1] : null;
  }

  function scan(o, depth) {
    if (!o || typeof o !== 'object' || depth > 40) return;
    if (o.video_info && o.video_info.variants) {
      var url = bestMp4(o.video_info.variants);
      if (url) {
        var mid = mediaIdFrom(o.media_url_https) || mediaIdFrom(url);
        if (mid) byMedia[mid] = url;
        var t = /status\/(\d+)/.exec(o.expanded_url || '');
        if (t && !byTweet[t[1]]) byTweet[t[1]] = url;
      }
    }
    for (var k in o) {
      if (Object.prototype.hasOwnProperty.call(o, k)) scan(o[k], depth + 1);
    }
  }

  var origFetch = window.fetch;
  window.fetch = function (input) {
    var p = origFetch.apply(this, arguments);
    try {
      var u = typeof input === 'string' ? input : (input && input.url) || '';
      if (API.test(u)) {
        p.then(function (r) {
          r.clone().json().then(function (j) { scan(j, 0); }).catch(function () {});
        }).catch(function () {});
      }
    } catch (e) {}
    return p;
  };

  var origOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    var self = this;
    if (API.test(String(url))) {
      this.addEventListener('load', function () {
        try { scan(JSON.parse(self.responseText), 0); } catch (e) {}
      });
    }
    return origOpen.apply(this, arguments);
  };

  // ---------- 2. Resolve which mp4 belongs to a given <video> ----------

  function resolve(video) {
    var src = video.currentSrc || video.src || '';
    if (/^https?:/.test(src) && /\.mp4(\?|$)/.test(src)) {
      return { url: src, id: mediaIdFrom(src) || String(Date.now()) };
    }
    // blob: -> look up the real mp4 captured from the API
    var mid = mediaIdFrom(video.poster || '');
    if (mid && byMedia[mid]) return { url: byMedia[mid], id: mid };

    var scope = video.closest('article') || document;
    var links = scope.querySelectorAll('a[href*="/status/"]');
    for (var i = 0; i < links.length; i++) {
      var m = /status\/(\d+)/.exec(links[i].getAttribute('href') || '');
      if (m && byTweet[m[1]]) return { url: byTweet[m[1]], id: m[1] };
    }
    var loc = /status\/(\d+)/.exec(location.pathname);
    if (loc && byTweet[loc[1]]) return { url: byTweet[loc[1]], id: loc[1] };
    return null;
  }

  // ---------- 3. Overlay buttons ----------

  function attach(video) {
    var host = video.closest('[data-testid="videoPlayer"]') || video.parentElement;
    if (!host || host.querySelector('.xbd-btn')) return;
    if (getComputedStyle(host).position === 'static') host.style.position = 'relative';

    var b = document.createElement('button');
    b.className = 'xbd-btn';
    b.textContent = '\u2B07 DOWNLOAD';
    b.style.cssText =
      'position:absolute;top:8px;right:8px;z-index:2147483647;padding:4px 10px;' +
      'font:700 11px sans-serif;color:#fff;background:rgba(29,155,240,.92);' +
      'border:0;border-radius:12px;';

    ['pointerdown', 'mousedown', 'touchstart'].forEach(function (ev) {
      b.addEventListener(ev, function (e) { e.stopPropagation(); });
    });

    b.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      var r = resolve(video);
      if (!r) {
        XBridge.toast('Video URL not captured. Reload the page and try again.');
        return;
      }
      XBridge.download(r.url, 'x_' + r.id + '.mp4');
    });

    host.appendChild(b);
  }

  function sweep() {
    var vids = document.querySelectorAll('video');
    for (var i = 0; i < vids.length; i++) attach(vids[i]);
  }

  setInterval(sweep, 1000);
})();
