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
        crawledData.clear()
        b.btnModeList.isSelected = m == "list"
        b.btnModeField.isSelected = m == "field"
        if (m == "list") {
            b.btnCrawl.text = "识别列表"
            toast("点击页面中的列表区域")
        } else {
            b.btnCrawl.text = "开始爬取"
            toast("点击页面元素设为字段")
        }
        b.webView.evaluateJavascript("setCrawlerMode('$m')", null)
    }

    private fun injectSelectionJS() {
        val js = """
function setCrawlerMode(m){window.__cmode__=m;window._sel=null;window._fields={};}
window.__cmode__='$mode';
window._sel=null;
window._fields={};
var _hl=null,_ov=null;

function findContainer(el){
  while(el&&el!==document.body){
    var t=el.tagName.toLowerCase();
    if(/table|tbody|ul|ol|select/.test(t))return el;
    var ch=el.children;
    if(ch.length>=3){
      var same=0;
      for(var i=0;i<ch.length;i++){
        if(ch[i].tagName===ch[0].tagName)same++;
      }
      if(same>=3)return el;
    }
    if(el.className&&/\b(list|items|result|row|grid|table)\b/i.test(el.className))return el;
    el=el.parentElement;
  }
  return null;
}

function getSelector(el){
  if(!el||el===document.body)return'';
  var path=[],cur=el;
  while(cur&&cur!==document.body){
    var t=cur.tagName.toLowerCase();
    if(cur.id){path.unshift('#'+cur.id);break;}
    var cls=Array.from(cur.classList).filter(function(c){return !/^crawler_/.test(c);});
    var s=t;
    if(cls.length)s+='.'+cls.join('.');
    if(cur.parentElement){
      var sib=cur.parentElement.children;
      var same=[];
      for(var i=0;i<sib.length;i++){
        if(sib[i].tagName===cur.tagName)same.push(i);
      }
      if(same.length>1)s+=':nth-child('+(same.indexOf(Array.from(sib).indexOf(cur))+1)+')';
    }
    path.unshift(s);
    cur=cur.parentElement;
  }
  return path.join(' > ');
}

function getText(el){return el.textContent.trim().substring(0,60);}

document.addEventListener('touchstart',function(e){
  var el=e.target;
  if(el.closest('.crawler_ov'))return;
  if(_hl){_hl.style.outline='';_hl=null;}
  if(_ov){_ov.remove();_ov=null;}
  
  if(window.__cmode__==='list'){
    var cont=findContainer(el);
    if(!cont)cont=el;
    cont.style.outline='3px solid #4CAF50';
    _hl=cont;
    var sel=getSelector(cont);
    var items=cont.querySelectorAll('tr,li,>div,>*,>tbody>tr');
    window._sel=sel;
    window.Crawler.onList(items.length||cont.children.length,sel);
  }else{
    var div=document.createElement('div');
    div.className='crawler_ov';
    div.style.cssText='position:fixed;bottom:70px;left:10px;right:10px;background:#333;color:#fff;padding:12px;border-radius:8px;z-index:99999;font-size:13px;';
    var sel=getSelector(el);
    div.innerHTML='<div style="margin-bottom:6px"><b>'+sel.substring(sel.length-30)+'</b><br>'+getText(el)+'</div>'+
      '<div style="display:flex;gap:6px">'+
      '<input class="crawler_fn" style="flex:1;padding:4px 8px;border:none;border-radius:4px;font-size:13px" placeholder="字段名">'+
      '<button class="crawler_ok" style="padding:4px 12px;background:#4CAF50;color:#fff;border:none;border-radius:4px">确定</button>'+
      '</div>';
    div.querySelector('.crawler_ok').onclick=function(){
      var nm=div.querySelector('.crawler_fn').value.trim();
      if(!nm)nm='field_'+(Object.keys(window._fields||{}).length+1);
      window._fields=window._fields||{};
      window._fields[nm]=sel;
      window.Crawler.onField(nm,sel,getText(el));
      div.remove();_ov=null;
    };
    document.body.appendChild(div);
    _ov=div;
  }
},{passive:true});
""" // language=JavaScript
        b.webView.evaluateJavascript(js, null)
    }

    private fun doCrawl() {
        if (mode == "list") {
            b.webView.evaluateJavascript("""
(function(){
  var sel=window._sel;
  if(!sel){window.Crawler.onError('请先点击列表区域');return;}
  var items=document.querySelectorAll(sel+' > tr, '+sel+' > li, '+sel+' > div, '+sel+' > tbody > tr, '+sel+' > *');
  if(items.length===0)items=document.querySelectorAll(sel+' *');
  window.Crawler.onList(items.length,sel);
})();
""".trimIndent(), null)
            return
        }

        if (fieldSelectors.isEmpty()) {
            toast("请先点选字段")
            return
        }
        val fieldsJs = fieldSelectors.map { (k, v) -> "\"$k\":\"$v\"" }.joinToString(",")
        b.webView.evaluateJavascript("""
(function(){
  var listSel=window._sel;
  var fields={$fieldsJs};
  if(!listSel){window.Crawler.onError('请先点击列表区域');return;}
  var items=document.querySelectorAll(listSel+' > tr, '+listSel+' > li, '+listSel+' > div, '+listSel+' > tbody > tr, '+listSel+' > *');
  if(items.length===0)items=document.querySelectorAll(listSel+' *');
  var data=[];
  items.forEach(function(row){
    var r={};
    for(var k in fields){
      var el=row.querySelector(fields[k]);
      if(!el)el=row;
      r[k]=el.textContent.trim().substring(0,200);
    }
    data.push(r);
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
                toast("列表: ${if (selector.isNotEmpty()) selector.substringAfterLast('>').trim() else "自动检测"} ($count 项)")
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
