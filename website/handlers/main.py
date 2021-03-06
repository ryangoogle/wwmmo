
import os
import webapp2 as webapp
from google.appengine.ext.webapp import blobstore_handlers
from google.appengine.ext import blobstore
from google.appengine.api import images
from google.appengine.api import mail
from google.appengine.api import users
from datetime import datetime, timedelta
import json

#from qrcode import main as qr
import handlers
from model import blog
import model.ping


class HomePage(handlers.BaseHandler):
  def get(self):
    self.render("index.html", {})


class PrivacyPolicyPage(handlers.BaseHandler):
  def get(self):
    self.render("privacy-policy.html", {})


class TermsOfServicePage(handlers.BaseHandler):
  def get(self):
    self.render("terms-of-service.html", {})


class RulesPage(handlers.BaseHandler):
  def get(self):
    self.render("rules.html", {})


class BlobUploadUrlPage(handlers.BaseHandler):
  def get(self):
    """Gets a new upload URL for uploading blobs."""
    if not users.get_current_user():
      self.response.set_status(403)
      return
    data = {'upload_url': blobstore.create_upload_url('/blob/upload-complete')}
    self.response.headers["Content-Type"] = "application/json"
    self.response.write(json.dumps(data))    


class BlobUploadCompletePage(blobstore_handlers.BlobstoreUploadHandler):
  def post(self):
    blob_info = self.get_uploads('file')[0]
    response = {'success': True,
                'blob_key': str(blob_info.key()),
                'size': blob_info.size,
                'filename': blob_info.filename}
    if "X-Blob" in self.request.headers:
      response['content_type'] = blob_info.content_type
      response['url'] = '/blob/' + str(blob_info.key())
    else:
      img = images.Image(blob_key=blob_info)
      img.im_feeling_lucky() # we have to do a transform in order to get dimensions
      img.execute_transforms()
      response['width'] = img.width
      response['height'] = img.height
      response['url'] = images.get_serving_url(blob_info.key(), 100, 0)
    self.response.headers["Content-Type"] = "application/json"
    self.response.write(json.dumps(response))


class BlobPage(blobstore_handlers.BlobstoreDownloadHandler):
  def get(self, blob_key):
    if not blobstore.get(blob_key):
      self.error(404)
    else:
      self.response.headers["Cache-Control"] = "public, max-age="+str(30*24*60*60) # 30 days
      self.response.headers["Expires"] = (datetime.now() + timedelta(days=30)).strftime("%a, %d %b %Y %H:%M:%S GMT")
      self.send_blob(blob_key)


class BlobDownloadPage(blobstore_handlers.BlobstoreDownloadHandler):
  def get(self, blob_key):
    blob_info = blobstore.get(blob_key)
    if not blob_info:
      self.error(404)
    else:
      self.response.headers["Cache-Control"] = "public, max-age="+str(30*24*60*60) # 30 days
      self.response.headers["Expires"] = (datetime.now() + timedelta(days=30)).strftime("%a, %d %b %Y %H:%M:%S GMT")
      self.response.headers["Content-Type"] = blob_info.content_type
      self.response.headers["Content-Disposition"] = str("attachment; filename=" + blob_info.filename)
      self.send_blob(blob_info)


class BlobInfoPage(handlers.BaseHandler):
  def get(self, blob_key):
    info = self._getBlobInfo(blob_key)
    if not info:
      self.error(404)
    self.response.headers["Content-Type"] = "application/json"
    self.response.write(json.dumps(info))

  def post(self, blob_key):
    info = self._getBlobInfo(blob_key)
    if not info:
      self.error(404)
    self.response.headers["Content-Type"] = "application/json"
    self.response.write(json.dumps(info))

  def _getBlobInfo(self, blob_key):
    blob_info = blobstore.get(blob_key)
    if not blob_info:
      return None

    size = None
    crop = None
    if self.request.POST.get("size"):
      size = int(self.request.POST.get("size"))
    if self.request.POST.get("crop"):
      crop = int(self.request.POST.get("crop"))

    return {"size": blob_info.size,
            "filename": blob_info.filename,
            "url": images.get_serving_url(blob_key, size, crop)}


class StatusPage(handlers.BaseHandler):
  def get(self):
    min_date = datetime.now() - timedelta(hours=24)

    last_ping = None
    pings = []
    # TODO: cache(?)
    query = model.ping.Ping.all().filter("date >", min_date).order("date")
    for ping in query:
      if not last_ping or last_ping.date < ping.date:
        last_ping = ping
      pings.append(ping)

    self.render("status.html", {"pings": pings, "last_ping": last_ping})


class SitemapPage(handlers.BaseHandler):
  def get(self):
    pages = []
    pages.append({"url": "/", "lastmod": "2012-05-14", "changefreq": "monthly", "priority": "0.8"})

    for post in blog.Post.all():
      # change frequency is normally going to be monthly, except for recent posts which
      # we'll set to daily (to account for possible comments)
      changefreq = "monthly"
      if post.posted + timedelta(days=30) > datetime.now():
        changefreq = "daily"
      pages.append({"url": ("/blog/%04d/%02d/%s" % (post.posted.year, post.posted.month, post.slug)),
                    "lastmod": ("%04d-%02d-%02d" % (post.updated.year, post.updated.month, post.updated.day)),
                    "changefreq": changefreq,
                    "priority": "1.0"})

    data = {"pages": pages, "base_url": "http://www.war-worlds.com"}

    self.response.headers["Content-Type"] = "text/xml"
    self.render("sitemap.xml", data)


class PlayStorePage(handlers.BaseHandler):
  def get(self):
    self.redirect("https://play.google.com/store/apps/details?id=au.com.codeka.warworlds")


class DonateThanksPage(handlers.BaseHandler):
  def get(self):
    self.render("donate-thanks.html", {})


app = webapp.WSGIApplication([("/", HomePage),
                              ("/privacy-policy", PrivacyPolicyPage),
                              ("/terms-of-service", TermsOfServicePage),
                              ("/rules", RulesPage),
                              ("/blob/upload-url", BlobUploadUrlPage),
                              ("/blob/upload-complete", BlobUploadCompletePage),
                              ("/blob/([^/]+)", BlobPage),
                              ("/blob/([^/]+)/download", BlobDownloadPage),
                              ("/blob/([^/]+)/info", BlobInfoPage),
                              ("/status/?", StatusPage),
                              ("/play-store", PlayStorePage),
                              ("/donate-thanks", DonateThanksPage),
                              ("/sitemap.xml", SitemapPage)],
                             debug=os.environ["SERVER_SOFTWARE"].startswith("Development"))
