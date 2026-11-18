# The edge: nginx with this folder's default.conf built in, so the same image runs on this
# machine and on the EC2 instance, whose Docker daemon cannot see this machine's files.
FROM nginx:1.28-alpine
COPY default.conf /etc/nginx/conf.d/default.conf
