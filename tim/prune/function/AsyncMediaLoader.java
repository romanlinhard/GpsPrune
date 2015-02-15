package tim.prune.function;

import java.io.File;

import tim.prune.App;
import tim.prune.DataSubscriber;
import tim.prune.GenericFunction;
import tim.prune.I18nManager;
import tim.prune.UpdateMessageBroker;
import tim.prune.data.AudioClip;
import tim.prune.data.MediaObject;
import tim.prune.data.Photo;
import tim.prune.data.Track;
import tim.prune.load.MediaHelper;
import tim.prune.load.MediaLoadProgressDialog;
import tim.prune.undo.UndoLoadAudios;
import tim.prune.undo.UndoLoadPhotos;

/**
 * Function to load media asynchronously,
 * either from inside a zip/kmz file or remotely
 */
public class AsyncMediaLoader extends GenericFunction
implements Runnable, Cancellable
{
	/** Archive from which points were loaded */
	private File _zipFile = null;
	/** Array of links */
	private String[] _linkArray = null;
	/** Track to use for connecting */
	private Track _track = null;
	/** Cancelled flag */
	private boolean _cancelled = false;


	/**
	 * Constructor
	 * @param inApp App object
	 * @param inLinkArray array of links
	 * @param inTrack Track object for connecting points
	 */
	public AsyncMediaLoader(App inApp, File inZipFile, String[] inLinkArray, Track inTrack)
	{
		super(inApp);
		_zipFile = inZipFile;
		_linkArray = inLinkArray;
		_track = inTrack;
	}

	/**
	 * Begin the load
	 */
	public void begin()
	{
		_cancelled = false;
		if (_linkArray != null)
			new Thread(this).start();
	}

	/** Cancel */
	public void cancel() {
		_cancelled = true;
	}

	/**
	 * @return the name key
	 */
	public String getNameKey() {
		return "function.asyncmediaload";
	}

	/**
	 * Execute the load in a separate thread
	 */
	public void run()
	{
		// Count links first so that progress bar can be shown
		int numLinks = 0;
		for (int i=0; i<_linkArray.length; i++) {
			if (_linkArray[i] != null) {
				numLinks++;
			}
		}
		if (numLinks <= 0) return;
		// Make progress dialog
		MediaLoadProgressDialog progressDialog = new MediaLoadProgressDialog(_app.getFrame(), this);
		// Make array to store results
		MediaObject[] media = new MediaObject[numLinks];
		int currLink = 0;
		for (int i=0; i<_linkArray.length && !_cancelled; i++)
		{
			if (_linkArray[i] != null)
			{
				MediaObject mf = MediaHelper.createMediaObject(_zipFile, _linkArray[i]);
				if (mf != null)
				{
					// attach media to point and set status
					_track.getPoint(i).attachMedia(mf);
					mf.setOriginalStatus(MediaObject.Status.TAGGED);
					mf.setCurrentStatus(MediaObject.Status.TAGGED);
					media[currLink] = mf;
					// update progress
					if (!_app.isBusyLoading())
						progressDialog.showProgress(currLink, numLinks);
					currLink++;
				}
				try {Thread.sleep(100);} catch (InterruptedException ie) {}
			}
		}
		progressDialog.close();

		// Wait until App is ready to receive media (may have to ask about append/replace etc)
		waitUntilAppReady();

		// Go through the loaded media and check if the points are still in the track
		int numPhotos = 0, numAudios = 0;
		for (currLink=0; currLink<numLinks; currLink++)
		{
			MediaObject mo = media[currLink];
			if (mo != null && _track.containsPoint(mo.getDataPoint()))
			{
				if (mo instanceof Photo)
				{
					_app.getTrackInfo().getPhotoList().addPhoto((Photo) mo);
					numPhotos++;
				}
				else if (mo instanceof AudioClip)
				{
					_app.getTrackInfo().getAudioList().addAudio((AudioClip) mo);
					numAudios++;
				}
			}
		}
		// Confirm and update
		if (numPhotos > 0) {
			_app.completeFunction(new UndoLoadPhotos(numPhotos, 0), "" + numPhotos + " " +
				I18nManager.getText(numPhotos == 1?"confirm.jpegload.single":"confirm.jpegload.multi"));
		}
		if (numAudios > 0) {
			_app.completeFunction(new UndoLoadAudios(numAudios), I18nManager.getText("confirm.audioload"));
		}
		UpdateMessageBroker.informSubscribers(DataSubscriber.DATA_ADDED_OR_REMOVED);
	}


	/**
	 * Wait until the App is ready
	 */
	private void waitUntilAppReady()
	{
		long waitInterval = 500; // milliseconds
		while (_app.isBusyLoading())
		{
			try {Thread.sleep(waitInterval);} catch (InterruptedException ie) {}
			waitInterval *= 1.2;
		}
	}
}
