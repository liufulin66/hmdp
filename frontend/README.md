# 点赞、关注与动态前端文件

本目录保存配套黑马点评前端的页面和共享脚本。本次关注改动仅在仓库中完成，尚未部署。以后使用时以教程前端资源为基础，把下面文件复制到 Nginx 的 `html/hmdp/`，保留原有 `css/`、`imgs/`、Vue、axios、Element UI 和其他页面：

| 仓库文件 | Nginx 前端目录中的目标 |
| --- | --- |
| `index.html` | `index.html` |
| `info.html` | `info.html` |
| `blog-detail.html` | `blog-detail.html` |
| `js/blog-likes.js` | `js/blog-likes.js` |
| `other-info.html` | `other-info.html` |
| `js/follows.js` | `js/follows.js` |
| `js/common.js` | `js/common.js` |
| `js/feed.js` | `js/feed.js` |

页面根据当前用户状态发送 PUT 点赞或 DELETE 取消点赞，并使用接口返回的总数和状态更新界面。浏览器从历史缓存返回时，重新查询已展示笔记的点赞状态，保留分页和滚动位置。

详情页通过 `GET /blog/likes/{id}` 展示点赞用户头像。默认显示最先点赞的 3 位，按后端返回的 Redis 顺序展示；点赞、取消和返回页面时同步刷新。共享脚本引用带版本号，以后部署相关页面和共享脚本后重启后端，已打开的旧页面强制刷新一次。

在现有 Nginx `server` 块中合并 `nginx-html-cache.conf` 的 HTML 缓存规则，保留已有 `/api` 代理。先执行 `nginx -t`，通过后再重载。已打开的旧页面需要强制刷新一次，此后正常返回会自动同步。

后端和数据库配置见 [点赞实现说明](../docs/blog-likes.md)。前端测试使用 Node.js 内置测试工具：

```sh
node --test frontend/tests/*.test.cjs
```

关注按钮通过 `follows.js` 发送带固定 true/false 意图的 PUT，pending 期间禁用按钮，成功应用返回布尔值，失败重查并保留原意图供重试。`common.js` 保留原有登录跳转，并处理超时等没有 `response` 的网络错误。

`other-info.html` 复用已有“共同关注”标签，按用户 ID 升序每页 5 位，使用 `current` 分页，最多第 1000 页。原“笔记”标签的 `/blog/of/user` 接口尚未实现，不属于本次范围。完整请求链路与限制见 [关注模块说明](../docs/follows.md)。

`info.html` 的“关注”标签显示笔记动态，使用 `feed.js` 按 minTime/offset 滚动分页，每页 2 条。支持刷新、加载互斥、旧响应过滤、失败重试、空列表及结束状态，并保留点赞逻辑。详情见 [Feed 模块说明](../docs/feed.md)。本次未同步到外部 Nginx 目录。
