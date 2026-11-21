#!/usr/bin/env sh
# Rasterise the brand SVGs in mkdocs/docs/assets/brand into the PNGs the site and README use.
# Needs rsvg-convert (librsvg). Run after editing any brand SVG.
set -e
cd "$(dirname "$0")/../../mkdocs/docs"
rsvg-convert -w 512 -h 512 assets/brand/mark.svg -o logo.png
rsvg-convert -w 1280 -h 640 assets/brand/social-card.svg -o assets/brand/social-card.png
