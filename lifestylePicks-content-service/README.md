# lifestylePicks-content-service

默认端口 8084，拥有 `tb_blog`、`tb_blog_comments`、`tb_follow`。迁入笔记、点赞、关注、共同关注、Feed、评论和上传，保持既有前端路径。通过 common 获取当前用户，通过 api 的 UserClient 查询作者和点赞用户；不读取用户库或店铺库。

## 接口与身份

原 `/blog/**`、`/follow/**`、`/upload/**` 均由本服务处理，网关支持带 `/api` 前缀的入口。热门笔记 `/blog/hot` 和图片 `/imgs/**` 公开，其余内容接口要求登录。服务自身也校验 common 用户上下文。

评论新增基础接口：POST `/blog-comments` 创建，GET `/blog-comments/of/blog?blogId=...` 查询最多 100 条正常评论，DELETE `/blog-comments/{id}` 删除自己的评论。原单体评论控制器为空，本次补充上述能力，未实现审核和多层回复展示。

发布笔记会覆盖请求体的 userId，使用可信 UserContext，向粉丝的 `feed:{userId}` 写入笔记。点赞使用 `blog:liked:{blogId}`，关注集合使用 `follows:{userId}`，Feed 保留原滚动分页格式。当前沿用原内容业务的数据库/Redis更新方式，未引入可靠事件投递或跨存储原子事务。

## 配置与运行

父工程使用 JDK 8 构建后，运行 `ContentApplication` 或本模块可执行 JAR。

| 环境变量 | 默认值 |
| --- | --- |
| `CONTENT_PORT` | `8084` |
| `CONTENT_SERVICE_URI` | 网关默认 `http://127.0.0.1:8084` |
| `CONTENT_DB_URL` | 本地 MySQL `hmdp`，与之前服务相同 URL 参数 |
| `CONTENT_DB_USERNAME` / `CONTENT_DB_PASSWORD` | `root` / `mysql` |
| `USER_SERVICE_URI` | `http://127.0.0.1:8083`，供 api 查询用户资料 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DATABASE` | `127.0.0.1` / `6379` / `0` |
| `CONTENT_UPLOAD_DIR` | 当前工作目录下 `./uploads` |

原业务表可继续使用，启动不会自动导入 SQL。`db/content.sql` 只包含本领域三张表和示例数据，用于空库；生产迁库应导出最新业务数据再改 URL，本次没有修改现有数据库。

## 图片

支持 PNG/JPEG/GIF/BMP，校验图片可解析，单文件最大 10MB。文件写入 `blogs/{userId}/{uuid}.{ext}`，返回原前端需要的相对图片路径。删除接口兼容 `/imgs` 前缀，只允许删除当前用户目录内的普通文件，拒绝越界路径。

服务通过 `/imgs/**` 提供新文件。Nginx 已配置先读取原静态 `/imgs`，文件不存在时转发到网关的内容服务，因此原示例图片继续可用。更新 Nginx 后先 `nginx.exe -t` 再重载。多实例部署时应将上传目录配置为共享存储；当前仍是本地文件实现，未新增对象存储服务。

## 退出单体和回退

原单体四个内容控制器默认关闭。正常启动四个服务和网关即可，不需要单体。回退时手动设置旧单体 `hmdp.legacy-content.enabled=true`，并将网关 `CONTENT_SERVICE_URI` 改为单体地址；数据和文件需要保持最新。服务故障不会自动回退旧单体。

## 验证

父工程的 `scripts/verify_final_services.py` 不启动单体，在四个独立数据库上验证用户、店铺、内容、交易及网关。内容部分覆盖远程作者、身份覆盖、关注和共同关注、Feed、点赞、评论权限，以及图片上传读取删除和越界拒绝。
