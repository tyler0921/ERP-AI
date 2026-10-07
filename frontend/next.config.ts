import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Docker 이미지용 최소 실행 번들 (.next/standalone)
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
