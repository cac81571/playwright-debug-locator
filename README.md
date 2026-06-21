# Playwright Debug Locator

Playwright の CDP 接続 (`connectOverCDP`) を使って、既存ブラウザ上のページに対しロケーターが正しく動作するかを確認する Swing デスクトップツールです。

## 機能

- CDP エンドポイントへの接続 / 切断
- 接続中ブラウザのページ一覧表示
- Playwright ロケーター文字列の検証
  - テスト実行（count / visible / text / tagName）
  - Count
  - Highlight / Hide Highlight
  - Wait Visible
  - 一致要素の一覧表示

## 前提

- Java 17+
- Maven 3.9+
- Chromium 系ブラウザ（Chrome / Edge など）

## ブラウザの起動例

```powershell
# Chrome
& "C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222

# Edge
& "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" --remote-debugging-port=9222
```

起動後、アプリの Endpoint URL に `http://localhost:9222` を入力して接続します。

## ビルドと起動

```powershell
mvn -q package
java -jar target/playwright-debug-locator-1.0.0-SNAPSHOT.jar
```

開発中は次でも起動できます。

```powershell
mvn -q exec:java
```

## Locator の例

| 種類 | 例 |
|------|-----|
| CSS | `#submit`, `.btn.primary` |
| Text | `text=Sign in` |
| Role | `role=button[name="Submit"]` |
| XPath | `xpath=//button[@type='submit']` |

`page.locator(...)` で解釈される Playwright セレクター文字列に対応しています。

## Groovy モード

「Groovy」タブでは Playwright の Java API に近いコードを動的実行できます。
`page`, `context`, `browser`, `playwright` がスクリプト内で利用可能です。

```groovy
// 1行式
page.locator("button")

// Java 風コード
page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in"))

// 複数行（最終行の式が Locator として評価されます）
def items = page.locator(".item")
items.filter(new Locator.FilterOptions().setVisible(true))
```

## Codegen

CDP 接続パネルの **Codegen** ボタンで Playwright Inspector（codegen）を起動できます。CDP 接続は不要です。

- 常に空のページから記録開始（CDP 接続中でも選択中ページの URL は使いません）
- ブラウザ: **Microsoft Edge**（`--channel=msedge`）
- 生成言語: Java（`--target=java`）

## 技術スタック

- Java 17
- Maven
- Groovy 4
- Swing + [FlatLaf](https://www.formdev.com/flatlaf/)
- [Playwright for Java](https://playwright.dev/java/)

## 注意

- CDP 接続は Playwright ネイティブ接続より機能が限定的です。高度な操作で問題が出る場合は `BrowserType.connect()` の利用を検討してください。
- Playwright API は単一スレッドで実行しています。UI からの操作はバックグラウンドワーカー経由で実行されます。
