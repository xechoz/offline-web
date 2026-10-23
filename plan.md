# 一个 web 离线加载 库

## 包括以下内容

1. 离线加载完整功能的库
2. demo 用例 apk
3. 使用 kotlin + compose

## 库的设计

### 包括

1. 离线包配置： 客户端请求，后台配置，包列表，包内容格式定义，安全配置
2. 配置后台需要的功能：自动打包 web url, 发布，同时更新 对应的 api 资源
3. 离线包下载，解压，校验
4. webview 加载 url 流程：拦截 url, 匹配离线包&资源，从本地读取资源
5. 其他：资源过期，废弃等边缘逻辑

### 核心 api 设计 

扩展 webview 或者 其他形式 接入

```
data class OfflineResource(
	...
)

// extension webview 或者其他class
fun WebView.initOfflineResource(resource) {
	// 在 chrome client, chrome web client 实现具体的拦截功能
}

internal class ChromeOfflineClient ...
internal class ChromeOfflineWebClient ...
```


## 使用

```kotlin
val webview = foo webview instance

val offlineSetting = OfflineResource(.....)
webview.initOfflineResource(offlineSetting)
```

## server 

server 实现一个简单的 web 打包，restful api 给 客户端调用

## demo

demo 里给出 完整的 android， server 使用 示例


## 发布

把 核心库 打包 发布到 maven