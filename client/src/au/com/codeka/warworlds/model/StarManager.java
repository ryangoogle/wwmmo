package au.com.codeka.warworlds.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeMap;

import org.apache.commons.collections.IteratorUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import au.com.codeka.BackgroundRunner;
import au.com.codeka.common.model.Simulation;
import au.com.codeka.common.protobuf.Messages;
import au.com.codeka.warworlds.api.ApiClient;
import au.com.codeka.warworlds.api.ApiException;
import au.com.codeka.warworlds.model.billing.IabException;
import au.com.codeka.warworlds.model.billing.Purchase;
import au.com.codeka.warworlds.model.billing.SkuDetails;

public class StarManager {
    private static StarManager sInstance = new StarManager();
    public static StarManager getInstance() {
        return sInstance;
    }

    private static final Logger log = LoggerFactory.getLogger(StarManager.class);
    private TreeMap<String, Star> mStars;
    private TreeMap<String, StarSummary> mStarSummaries;
    private TreeMap<String, List<StarFetchedHandler>> mStarUpdatedListeners;
    private List<StarFetchedHandler> mAllStarUpdatedListeners;

    private StarManager() {
        mStars = new TreeMap<String, Star>();
        mStarSummaries = new TreeMap<String, StarSummary>();
        mStarUpdatedListeners = new TreeMap<String, List<StarFetchedHandler>>();
        mAllStarUpdatedListeners = new ArrayList<StarFetchedHandler>();
    }

    /**
     * Call this to register your interest in when a particular star is updated. Any time that
     * star is re-fetched from the server, your \c StarFetchedHandler will be called.
     */
    public void addStarUpdatedListener(String starKey, StarFetchedHandler handler) {
        if (starKey == null) {
            mAllStarUpdatedListeners.add(handler);
            return;
        }

        synchronized(mStarUpdatedListeners) {
            List<StarFetchedHandler> listeners = mStarUpdatedListeners.get(starKey);
            if (listeners == null) {
                listeners = new ArrayList<StarFetchedHandler>();
                mStarUpdatedListeners.put(starKey, listeners);
            }
            listeners.add(handler);
        }
    }

    /**
     * Removes the given \c StarFetchedHandler from receiving updates about refreshed stars.
     */
    public void removeStarUpdatedListener(StarFetchedHandler handler) {
        synchronized(mStarUpdatedListeners) {
            for (Object o : IteratorUtils.toList(mStarUpdatedListeners.keySet().iterator())) {
                String starKey = (String) o;

                List<StarFetchedHandler> listeners = mStarUpdatedListeners.get(starKey);
                listeners.remove(handler);

                if (listeners.isEmpty()) {
                    mStarUpdatedListeners.remove(starKey);
                }
            }
        }

        mAllStarUpdatedListeners.remove(handler);
    }

    public void fireStarUpdated(final Star star) {
        synchronized(mStarUpdatedListeners) {
            List<StarFetchedHandler> listeners = mStarUpdatedListeners.get(star.getKey());
            if (listeners != null) {
                for (StarFetchedHandler handler : listeners) {
                    fireHandler(handler, star);
                }
            }

            // also anybody who's interested in ALL stars
            for (StarFetchedHandler handler : mAllStarUpdatedListeners) {
                fireHandler(handler, star);
            }
        }

        // also let a couple of the other Managers know
        SectorManager.getInstance().onStarUpdate(star);
        BuildManager.getInstance().onStarUpdate(star);
    }

    private void fireHandler(final StarFetchedHandler handler, final Star star) {
        try {
            if (handler instanceof ContextWrapper) {
                ContextWrapper ctx = (ContextWrapper) handler;
                new Handler(ctx.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        handler.onStarFetched(star);
                    }
                });
            } else {
                handler.onStarFetched(star);
            }
        } catch (Exception e) {
            log.warn("Ignored exception in onStarFetched...", e);
        }
    }

    public void refreshStar(Context context, Star s) {
        refreshStar(context, s.getKey());
    }

    public void refreshStar(Context context, String starKey) {
        refreshStar(context, starKey, false);
    }

    public boolean refreshStar(Context context, String starKey, boolean onlyIfCached) {
        if (onlyIfCached && !mStars.containsKey(starKey)) {
            return false;
        }

        requestStar(context, starKey, true, new StarFetchedHandler() {
            @Override
            public void onStarFetched(Star s) {
                // When a star is explicitly refreshed, it's usually because it's changed somehow.
                // Generally that also means the sector has changed.
                SectorManager.getInstance().refreshSector(s.getSectorX(), s.getSectorY());
            }
        });

        return true;
    }

    public void requestStarSummary(final Context context, final String starKey,
                                   final StarSummaryFetchedHandler callback) {
        StarSummary ss = mStarSummaries.get(starKey);
        if (ss != null) {
            callback.onStarSummaryFetched(ss);
            return;
        }

        ss = mStars.get(starKey);
        if (ss != null) {
            callback.onStarSummaryFetched(ss);
            return;
        }

        new BackgroundRunner<StarSummary>() {
            @Override
            protected StarSummary doInBackground() {
                return requestStarSummarySync(context, starKey);
            }

            @Override
            protected void onComplete(StarSummary starSummary) {
                if (starSummary != null) {
                    callback.onStarSummaryFetched(starSummary);
                }
            }
        }.execute();
    }

    public void requestStarSummaries(final Context context, final Collection<String> starKeys,
                                     final StarSummariesFetchedHandler callback) {
        final ArrayList<StarSummary> summaries = new ArrayList<StarSummary>();
        final ArrayList<String> toFetch = new ArrayList<String>();

        for (String starKey : starKeys) {
            StarSummary ss = mStarSummaries.get(starKey);
            if (ss != null) {
                summaries.add(ss);
                continue;
            }

            ss = mStars.get(starKey);
            if (ss != null) {
                summaries.add(ss);
                continue;
            }

            toFetch.add(starKey);
        }

        if (toFetch.size() == 0) {
            callback.onStarSummariesFetched(summaries);
        } else {
            new BackgroundRunner<List<StarSummary>>() {
                @Override
                protected List<StarSummary> doInBackground() {
                    return requestStarSummariesSync(context, toFetch);
                }

                @Override
                protected void onComplete(List<StarSummary> stars) {
                    if (stars != null) {
                        for (StarSummary star : stars) {
                            summaries.add(star);
                        }
                    }
                    callback.onStarSummariesFetched(summaries);
                }
            }.execute();
        }
    }

    /**
     * Like \c requestStarSummary but runs synchronously. Useful if you're
     * @param context
     * @param starKey
     * @return
     */
    public StarSummary requestStarSummarySync(Context context, String starKey) {
        StarSummary ss = mStarSummaries.get(starKey);
        if (ss != null) {
            return ss;
        }

        ss = mStars.get(starKey);
        if (ss != null) {
            return ss;
        }

        ss = loadStarSummary(context, starKey);
        if (ss != null) {
            return ss;
        }

        // no cache StarSummary, fetch the full star
        return doFetchStar(context, starKey);
    }

    public List<StarSummary> requestStarSummariesSync(Context context, Collection<String> starKeys) {
        ArrayList<StarSummary> starSummaries = new ArrayList<StarSummary>();
        for (String starKey : starKeys) {
            // TODO: this could be more efficient...
            StarSummary ss = requestStarSummarySync(context, starKey);
            if (ss != null) {
                starSummaries.add(ss);
            }
        }
        return starSummaries;
    }

    /**
     * Requests the details of a star from the server, and calls the given callback when it's
     * received. The callback is called on the main thread.
     */
    public void requestStar(final Context context, final String starKey, boolean force,
                            final StarFetchedHandler callback) {
        Star s = mStars.get(starKey);
        if (s != null && !force) {
            callback.onStarFetched(s);
            return;
        }

        new BackgroundRunner<Star>() {
            @Override
            protected Star doInBackground() {
                Star star = doFetchStar(context, starKey);
                if (star != null) {
                    log.debug(String.format("STAR[%s] has %d fleets.", star.getKey(),
                              star.getFleets() == null ? 0 : star.getFleets().size()));
                }

                if (star != null && !RealmManager.i.getRealm().isAlpha()) {
                    // the alpha realm will have already simulated the star, but other realms
                    // will need to simulate first.
                    Simulation sim = new Simulation();
                    sim.simulate(star);
                }
                return star;
            }

            @Override
            protected void onComplete(Star star) {
                if (star == null) {
                    return; // BAD!
                }

                // if we had the star summary cached, remove it (cause the star itself is newer)
                mStarSummaries.remove(starKey);
                mStars.put(starKey, star);

                if (callback != null) {
                    callback.onStarFetched(star);
                }
                fireStarUpdated(star);
            }
        }.execute();
    }

    public void renameStar(final Context context, final Purchase purchase,
                           final Star star, final String newName) {
        new BackgroundRunner<Star>() {
            @Override
            protected Star doInBackground() {
                String url = "stars/"+star.getKey();

                String price = "???";
                SkuDetails sku = null;
                try {
                    sku = PurchaseManager.getInstance().getInventory().getSkuDetails(purchase.getSku());
                } catch (IabException e1) {
                }
                if (sku != null) {
                    price = sku.getPrice();
                }

                Messages.StarRenameRequest pb = Messages.StarRenameRequest.newBuilder()
                        .setStarKey(star.getKey())
                        .setOldName(star.getName())
                        .setNewName(newName)
                        .setPurchaseOrderId(purchase.getOrderId())
                        .setPurchaseTime(purchase.getPurchaseTime())
                        .setPurchasePrice(price)
                        .setPurchaseDeveloperPayload(purchase.getDeveloperPayload())
                        .build();

                Messages.Star star_pb;
                try {
                    star_pb = ApiClient.putProtoBuf(url, pb, Messages.Star.class);
                    Star star = new Star();
                    star.fromProtocolBuffer(star_pb);

                    updateStarSummary(context, star);
                    return star;
                } catch (ApiException e) {
                    log.error("Error renaming star!", e);
                    return null;
                }
            }

            @Override
            protected void onComplete(Star star) {
                if (star == null) {
                    return; //TODO: bad!
                }

                // if we had the star summary cached, remove it (cause the star itself is newer)
                mStarSummaries.remove(star.getKey());
                mStars.put(star.getKey(), star);

                fireStarUpdated(star);
            }
        }.execute();
    }

    private Star doFetchStar(final Context context, final String starKey) {
        Star star = null;

        try {
            String url = "stars/"+starKey;

            Messages.Star pb = ApiClient.getProtoBuf(url, Messages.Star.class);
            star = new Star();
            star.fromProtocolBuffer(pb);
        } catch(Exception e) {
            // TODO: handle exceptions
            log.error(ExceptionUtils.getStackTrace(e));
        }

        if (star != null) {
            updateStarSummary(context, star);
        }

        return star;
    }

    /**
     * This is called when we fetch a new \c StarSummary, we'll want to cache it.
     */
    private static void updateStarSummary(Context context, StarSummary summary) {
        File summaryFile = getSummaryFile(context, summary.getKey());

        Messages.Star.Builder starpb = Messages.Star.newBuilder();
        summary.toProtocolBuffer(starpb);
        Messages.Star pb = starpb.build();

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(summaryFile.getCanonicalPath());
            pb.writeTo(fos);
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                }
            }
        }
    }

    /**
     * Attempts to load a \c StarSummary back from the cache directory.
     */
    private static StarSummary loadStarSummary(Context context, String starKey) {
        File summaryFile = getSummaryFile(context, starKey);
        if (!summaryFile.exists()) {
            return null;
        }

        FileInputStream fis = null;
        try {
            fis = new FileInputStream(summaryFile.getCanonicalPath());
            Messages.Star pb = Messages.Star.newBuilder().mergeFrom(fis).build();

            StarSummary ss = new StarSummary();
            ss.fromProtocolBuffer(pb);
            return ss;
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                }
            }
        }

        return null;
    }

    private static File getSummaryFile(Context context, String starKey) {
        File cacheDir = context.getCacheDir();

        File starsDir = new File(cacheDir, "stars");
        starsDir.mkdirs();

        return new File(starsDir, starKey+".cache");
    }

    public interface StarFetchedHandler {
        void onStarFetched(Star s);
    }
    public interface StarSummaryFetchedHandler {
        void onStarSummaryFetched(StarSummary s);
    }
    public interface StarSummariesFetchedHandler {
        void onStarSummariesFetched(Collection<StarSummary> stars);
    }
}