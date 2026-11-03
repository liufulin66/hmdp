# 点赞、关注、动态、附近商铺与签到前端文件

站点 UV 已在首页、商铺列表/详情、笔记详情、自己/他人主页接入 `js/site-visits.js?v=20261103-uv-1`。内容成功渲染后每页最多尝试一次，使用独立请求，不干扰登录和业务错误处理。新增 `statistics.html`、`js/site-statistics.js`、`css/statistics.css`；个人主页提供管理员入口，权限由后端校验。相关10个文件已备份后同步本机 Nginx，细节及可用状态见 [UV说明](../docs/site-uv.md)。

签到版本已将 `info.html`、`js/user-sign.js`、`css/user-sign.css` 备份后同步到本机 Nginx，资源版本 `20261103-sign-3`。支持截至今天的跨月连续签到；写入成功后单独查询统计，统计失败保留已签到并可重试。下面其他模块的“未部署”描述是各自实现时的记录，签到同步不代表覆盖其他页面。签到接口、备份路径及实际浏览器验证见 [签到说明](../docs/user-sign.md)。

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
| `js/user-sign.js` | `js/user-sign.js` |
| `css/user-sign.css` | `css/user-sign.css` |
| `shop-list.html` | `shop-list.html` |
| `css/shop-list.css` | `css/shop-list.css` |
| `js/shop-nearby.js` | `js/shop-nearby.js` |
| `js/blog-comments.js` | `js/blog-comments.js` |

页面根据当前用户状态发送 PUT 点赞或 DELETE 取消点赞，并使用接口返回的总数和状态更新界面。浏览器从历史缓存返回时，重新查询已展示笔记的点赞状态，保留分页和滚动位置。

详情页通过 `GET /blog/likes/{id}` 展示点赞用户头像。默认显示最先点赞的 3 位，按后端返回的 Redis 顺序展示；点赞、取消和返回页面时同步刷新。共享脚本引用带版本号，以后部署相关页面和共享脚本后重启后端，已打开的旧页面强制刷新一次。

在现有 Nginx `server` 块中合并 `nginx-html-cache.conf` 的 HTML 缓存规则，保留已有 `/api` 代理。先执行 `nginx -t`，通过后再重载。已打开的旧页面需要强制刷新一次，此后正常返回会自动同步。

后端和数据库配置见 [点赞实现说明](../docs/blog-likes.md)。前端测试使用 Node.js 内置测试工具：

```sh
node --test frontend/tests/*.test.cjs
```

关注按钮通过 `follows.js` 发送带固定 true/false 意图的 PUT，pending 期间禁用按钮，成功应用返回布尔值，失败重查并保留原意图供重试。`common.js` 保留原有登录跳转，并处理超时等没有 `response` 的网络错误。

`other-info.html` 复用已有“共同关注”标签，按用户 ID 升序每页 5 位，使用 `current` 分页，最多第 1000 页。“笔记”标签使用已实现的 `GET /blog/of/user?id=<目标用户ID>&current=<页码>`，按笔记时间与 ID 倒序分页，支持首次加载、加载更多、空列表和失败重试。完整请求链路与限制见 [关注模块说明](../docs/follows.md)。

`info.html` 的“关注”标签显示笔记动态，使用 `feed.js` 按 minTime/offset 滚动分页，每页 2 条。支持刷新、加载互斥、旧响应过滤、失败重试、空列表及结束状态，并保留点赞逻辑。详情见 [Feed 模块说明](../docs/feed.md)。本次未同步到外部 Nginx 目录。

`shop-list.html` 与原样式来自外部 Nginx 的现有页面，已按确认纳入仓库；本次没有写回外部目录。列表默认普通分类，可主动选择明确标注的教程演示位置或输入与商铺同源的坐标。没有使用写死坐标冒充真实定位；坐标系未知时不接入浏览器 GPS。

`shop-nearby.js` 请求 `pageInfo=true`，支持半径/分类/坐标变化重置分页、加载互斥、旧响应失效、错误重试、空结果/结束状态及米/公里距离展示。新版 `common.js` 保留合法 0 坐标并编码参数。本版不提供未实现的人气/评分排序。使用前先完成数据库迁移及管理员 GEO 初始化，见 [附近商铺说明](../docs/nearby-shops.md)。

`blog-detail.html` 用 `blog-comments.js` 替换写死评论与数量，支持一级评论、回复、自删占位和独立滚动分页。超时重试保持原 requestId，编辑提交意图后使用新编号；代次与计数版本避免旧响应破坏当前结果。评论按普通文本渲染。使用前执行已核实的评论迁移；本次没有写回外部 Nginx 目录，见 [评论模块说明](../docs/blog-comments.md)。
