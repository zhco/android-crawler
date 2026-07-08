package com.zhco.crawler

import android.annotation.SuppressLint
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zhco.crawler.databinding.ActivityMainBinding
import java.io.OutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    private var listSelector = ""
    private var fieldSelectors = mutableMapOf<String, String>()
    private var mode = "list" // "list" | "field"
    private var crawledData = mutableListOf<Map<String, String>>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        with(b.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        }
        b.webView.addJavascriptInterface(WebBridge(), "Crawler")

        b.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                b.progressBar.visibility = android.view.View.GONE
                injectSelectionJS()
            }
        }
        b.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, progress: Int) {
                b.progressBar.progress = progress
                b.progressBar.visibility = if (progress < 100) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        b.btnGo.setOnClickListener {
            val url = b.etUrl.text.toString().trim()
            if (url.isEmpty()) return@setOnClickListener
            val fullUrl = if (url.startsWith("http")) url else "https://$url"
            b.etUrl.setText(fullUrl)
            b.webView.loadUrl(fullUrl)
        }

        b.btnModeList.setOnClickListener { setMode("list") }
        b.btnModeField.setOnClickListener { setMode("field") }
        b.btnCrawl.setOnClickListener { doCrawl() }
        b.btnExport.setOnClickListener { exportCSV() }

        setMode("list")
    }

    private fun setMode(m: String) {
        mode = m
        fieldSelectors.clear()
        b.btnModeList.isSelected = m == "list"
        b.btnModeField.isSelected = m == "field"
        b.btnCrawl.text = if (m == "list") "选列表 → 爬取" else "选字段 → 爬取"
        b.webView.evaluateJavascript("setCrawlerMode('$m')", null)
    }

    private fun injectSelectionJS() {
        val js = """
function setCrawlerMode(m){window.__cmode__=m;}
window.__cmode__='$mode';
(function(){
var old=document.querySelectorAll.bind(document);
var ov=null,od=null;
function getSelector(el){
  if(!el||el===document.body)return '';
  var path=[],cur=el;
  while(cur&&cur!==document.body){
    var tag=cur.tagName.toLowerCase();
    if(cur.id){path.unshift('#'+cur.id);break;}
    var cls=Array.from(cur.classList).filter(c=>!/^crawler_/.test(c));
    var s=tag;
    if(cls.length)s+='.'+cls.join('.');
    if(cur.parentElement){
      var sib=Array.from(cur.parentElement.children).filter(c=>c.tagName===cur.tagName);
      if(sib.length>1)s+=':nth-child('+(Array.from(cur.parentElement.children).indexOf(cur)+1)+')';
    }
    path.unshift(s);
    cur=cur.parentElement;
  }
  return path.join(' > ');
}
function getText(el){return el.textContent.trim().substring(0,80);}
document.addEventListener('touchstart',function(e){
  var el=e.target;
  if(el.closest('.crawler_overlay'))return;
  if(od)od.style.outline='';
  if(ov){ov.remove();ov=null;}
  if(window.__cmode__==='list'){
    el.style.outline='3px solid #2196F3';
    od=el;
  }else{
    var div=document.createElement('div');
    div.className='crawler_overlay';
    div.style.cssText='position:fixed;bottom:70px;left:10px;right:10px;background:#333;color:#fff;padding:12px;border-radius:8px;z-index:99999;font-size:14px;';
    div.innerHTML='<b>选择器:</b> '+getSelector(el)+'<br><b>示例:</b> '+getText(el)+
      '<br><button class="crawler_btn" style="margin-top:8px;padding:6px 16px;background:#4CAF50;color:#fff;border:none;border-radius:4px;">设为字段</button>';
    div.querySelector('.crawler_btn').onclick=function(){
      var sel=getSelector(el);
      var name=prompt('字段名:',sel.replace(/[^a-z]/g,'_').substring(0,20));
      if(name)window.Crawler.onField(name,sel,getText(el));
      div.remove();ov=null;
    };
    document.body.appendChild(div);
    ov=div;
  }
},{passive:true});
})();
""" // language=JavaScript
        b.webView.evaluateJavascript(js, null)
    }

    private fun doCrawl() {
        if (mode == "list" && listSelector.isEmpty()) {
            b.webView.evaluateJavascript("""
                (function(){
                    var el=document.querySelector('body *');
                    var sel=window.__lastListSel__;
                    var items=sel?document.querySelectorAll(sel):[];
                    var count=items.length||(el&&el.children?el.children.length:0);
                    window.Crawler.onList(count,sel||'body>*');
                })();
            """.trimIndent()) { result ->
                if (result?.contains("\"count\":") == true) return@evaluateJavascript
                // Try to find list automatically
                b.webView.evaluateJavascript("""
(function(){
var best=null,bestCount=0;
var els=document.querySelectorAll('table,ul,ol,div.list,div.items,.result_list,.data_list');
els.forEach(function(el){
  var rows=el.querySelectorAll('tr,li,div.item,div.row');
  if(rows.length>bestCount){best=rows[0].parentElement;bestCount=rows.length;}
});
if(best){
  var sel=(best.id?'#'+best.id:best.tagName.toLowerCase()+'.'+Array.from(best.classList).join('.'));
  window.Crawler.onAutoList(sel,bestCount);
}else{
  window.Crawler.onError('未找到列表元素，请先点选列表项');
}
})();
                """.trimIndent(), null)
            }
            return
        }
        // Execute crawl
        if (mode == "field" && fieldSelectors.isEmpty()) {
            toast("请先点选字段")
            return
        }
        val fieldsJs = fieldSelectors.map { (k, v) -> "\"$k\":\"$v\"" }.joinToString(",")
        b.webView.evaluateJavascript("""
(function(){
  var listSel='$listSelector';
  var fields={$fieldsJs};
  var items=document.querySelectorAll(listSel);
  var data=[];
  items.forEach(function(item){
    var row={};
    for(var key in fields){
      var el=item.querySelector(fields[key]);
      row[key]=el?el.textContent.trim().substring(0,200):'';
    }
    data.push(row);
  });
  window.Crawler.onResult(JSON.stringify(data));
})();
""".trimIndent(), null)
    }

    private fun exportCSV() {
        if (crawledData.isEmpty()) { toast("暂无数据"); return }
        val sb = StringBuilder()
        val headers = crawledData.first().keys
        sb.appendLine(headers.joinToString(",") { "\"${it}\"" })
        crawledData.forEach { row ->
            sb.appendLine(headers.joinToString(",") { "\"${row[it] ?: ""}\"" })
        }
        val csv = "\uFEFF$sb"
        try {
            val filename = "crawler_${System.currentTimeMillis()}.csv"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { os -> os.write(csv.toByteArray()) }
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                java.io.File(dir, filename).writeText(csv)
            }
            toast("已导出到 Downloads/$filename (${crawledData.size}条)")
        } catch (e: Exception) {
            toast("导出失败: ${e.message}")
        }
    }

    private fun toast(msg: String) {
        val t = Toast.makeText(this, msg, Toast.LENGTH_SHORT)
        t.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 200)
        t.show()
    }

    inner class WebBridge {
        @JavascriptInterface
        fun onField(name: String, selector: String, sample: String) {
            runOnUiThread {
                fieldSelectors[name] = selector
                toast("字段 [$name] = $selector ($sample)")
            }
        }

        @JavascriptInterface
        fun onList(count: Int, selector: String) {
            runOnUiThread {
                listSelector = selector
                toast("列表: ${if (selector.isNotEmpty()) selector else "自动检测"} ($count 项)")
            }
        }

        @JavascriptInterface
        fun onAutoList(selector: String, count: Int) {
            runOnUiThread {
                listSelector = selector
                toast("自动识别列表: $selector ($count 项)")
            }
        }

        @JavascriptInterface
        fun onResult(json: String) {
            runOnUiThread {
                try {
                    val arr = org.json.JSONArray(json)
                    crawledData.clear()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val row = mutableMapOf<String, String>()
                        obj.keys().forEach { row[it] = obj.getString(it) }
                        crawledData.add(row)
                    }
                    toast("爬取完成: ${crawledData.size} 条数据 (点击导出CSV)")
                } catch (e: Exception) {
                    toast("解析失败: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun onError(msg: String) {
            runOnUiThread { toast(msg) }
        }
    }
}
