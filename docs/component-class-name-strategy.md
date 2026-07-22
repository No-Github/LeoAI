# Java Component 类名高级配置

`Puppet.componentClassNameStrategy` 使用 JSON 保存，每个 Java Puppet 独立生效。
配置为空时沿用原有应用类名画像。

## 配置格式

```json
{"enabled":true,"mode":"INNER_CLASS"}
```

可选 `mode`：

- `APPLICATION`：原有应用类名画像
- `INNER_CLASS`：`Outer$Inner`
- `LAMBDA_SHAPED`：`Outer$$Lambda$N`
- `PROXY_SHAPED`：`application.proxy.$ProxyN`

同一会话与组件始终生成相同类名，不同组件自动获得不同名称。

## 预览接口

```http
POST /platform/puppet-manage/component-class-name/preview
Content-Type: application/json
```

```json
{
  "sessionKey": "preview-session",
  "strategy": {
    "enabled": true,
    "mode": "LAMBDA_SHAPED"
  },
  "components": ["BasicInfoComponent", "FileComponent"]
}
```
