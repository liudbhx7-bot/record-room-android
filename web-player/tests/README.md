# 网页资料库回归

在仓库根目录构建并启动本地预览：

```sh
python3 web-player/build.py --out work/web-preview
python3 -m http.server 8000 --bind 127.0.0.1 --directory work/web-preview
```

在另一终端从仓库根目录执行。依赖只装到被忽略的工作目录：

```sh
npm install --prefix work/web-qa playwright@1.62.1
work/web-qa/node_modules/.bin/playwright install webkit chromium
NODE_PATH=./work/web-qa/node_modules node web-player/tests/library.cjs http://localhost:8000
NODE_PATH=./work/web-qa/node_modules TEST_BROWSER=chromium node web-player/tests/library.cjs http://localhost:8000
```

脚本会在临时目录生成 60 秒的 24-bit/96 kHz 双声道 WAV（34,560,044 字节）；也可以把自己的大 WAV 路径作为最后一个参数，脚本只改临时副本。每次使用独立浏览器资料库，覆盖重复与改名导入、旧版记录、同名异曲、取消与确认移除、删除后继续播放、重开，以及无演示曲时移除最后一首。桌面 WebKit 结果不能替代 iPhone 真机验收。
