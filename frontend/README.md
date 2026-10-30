# 点赞前端文件

本目录保存已应用到配套黑马点评前端的页面和共享脚本。以教程前端资源为基础，把下面文件复制到 Nginx 的 `html/hmdp/`，保留原有 `css/`、`imgs/`、Vue、axios、Element UI 和其他页面：

| 仓库文件 | Nginx 前端目录中的目标 |
| --- | --- |
| `index.html` | `index.html` |
| `info.html` | `info.html` |
| `blog-detail.html` | `blog-detail.html` |
| `js/blog-likes.js` | `js/blog-likes.js` |

页面根据当前用户状态发送 PUT 点赞或 DELETE 取消点赞，并使用接口返回的总数和状态更新界面。浏览器从历史缓存返回时，重新查询已展示笔记的点赞状态，保留分页和滚动位置。

在现有 Nginx `server` 块中合并 `nginx-html-cache.conf` 的 HTML 缓存规则，保留已有 `/api` 代理。先执行 `nginx -t`，通过后再重载。已打开的旧页面需要强制刷新一次，此后正常返回会自动同步。

后端和数据库配置见 [点赞实现说明](../docs/blog-likes.md)。前端测试使用 Node.js 内置测试工具：

```sh
node --test frontend/tests/blog-likes.test.cjs
```
