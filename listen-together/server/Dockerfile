FROM node:20-alpine

WORKDIR /app

# Copy server package files
COPY package*.json ./

# Install production dependencies
RUN npm ci --only=production

# Copy server source
COPY . .

# Expose port (Render/Railway/Cloud providers pass PORT dynamically)
ENV PORT=4000
EXPOSE 4000

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:${PORT}/health || exit 1

# Start the sync server
CMD ["node", "server.js"]
