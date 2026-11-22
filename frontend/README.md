# SmartBrief Frontend


SmartBrief（AI要約・翻訳SaaSプラットフォーム）のフロントエンドです。


## Tech Stack


- React 19 / TypeScript 5.9
- React Router v7
- Zustand 5（状態管理）
- Vite（rolldown-vite）
- Nginx（配信層でセキュリティヘッダー／CSP を付与）


## Development


    npm install
    npm run dev


## Build


    npm run build


本番では Docker（react-app）でビルドし、Nginx から配信します。
