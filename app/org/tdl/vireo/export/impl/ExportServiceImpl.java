package org.tdl.vireo.export.impl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.io.FilenameUtils;
import org.tdl.vireo.export.ChunkStream;
import org.tdl.vireo.export.ExportPackage;
import org.tdl.vireo.export.ExportService;
import org.tdl.vireo.export.Packager;
import org.tdl.vireo.model.Person;
import org.tdl.vireo.model.PersonRepository;
import org.tdl.vireo.model.Submission;
import org.tdl.vireo.search.SearchDirection;
import org.tdl.vireo.search.SearchFilter;
import org.tdl.vireo.search.SearchOrder;
import org.tdl.vireo.search.Searcher;
import org.tdl.vireo.security.SecurityContext;

import play.Logger;
import play.jobs.Job;


/**
 * Implementation of the export service interface.
 * 
 * @author <a href="http://www.scottphillips.com">Scott Phillips</a>
 */
public class ExportServiceImpl implements ExportService {

	public final static String MIME_TYPE = "application/zip";
	public final static int BUFFER_SIZE = 10; // Each chunk may be big.
	
	// The repository of people
	public PersonRepository personRepo;
	
	// The searcher used to find submissions in a batch.
	public Searcher searcher;
	
	// The security context, who's logged in.
	public SecurityContext context;
	
	// List of currently scheduled deposit jobs
	public static Set<ExportJob> jobQueue = Collections.synchronizedSet(new HashSet<ExportJob>()); 

	/**
	 * @param searcher
	 *            Set the searcher used for identify batch of submissions to be
	 *            processed.
	 */
	public void setSearcher(Searcher searcher) {
		this.searcher = searcher;
	}

	/**
	 * @param repo
	 *            The person repository
	 */
	public void setPersonRepository(PersonRepository repo) {
		this.personRepo = repo;
	}

	/**
	 * @param context
	 *            The security context managing who is currently logged in.
	 */
	public void setSecurityContext(SecurityContext context) {
		this.context = context;
	}	
	
	@Override
	public boolean isExportRunning() {
		return !jobQueue.isEmpty();
	}

	@Override
	public ChunkStream export(Packager packager, SearchFilter filter) {

		if (packager == null)
			throw new IllegalArgumentException("A packager is required");
		
		if (filter == null)
			throw new IllegalArgumentException("A search filter is required");
		
		ChunkStreamImpl stream = new ChunkStreamImpl(
				MIME_TYPE, 
				"attachment; filename="+packager.getBeanName()+".zip", 
				BUFFER_SIZE);
		
		new ExportJob(packager,filter,stream).now();
		
		return stream;
	}
	
	/**
	 * Background job to export
	 */
	public class ExportJob extends Job {
		
		// Member fields
		public final Packager packager;
		public final SearchFilter filter;
		public final OutputStream out;
		public final Long personId;
		
		/**
		 * Construct a new export job.
		 * 
		 * @param packager
		 *            The packager which will generate content.
		 * @param filter
		 *            The filter to select submissions.
		 * @param out
		 *            The output stream where to send the export too.
		 */
		public ExportJob(Packager packager, SearchFilter filter, OutputStream out) {
			this.packager = packager;
			this.filter = filter;
			this.out = out;
			
			if (context.getPerson() != null) {
				
				if (!context.isReviewer())
					throw new SecurityException("Not authorized to preform export operation.");
				
				this.personId = context.getPerson().getId();
			} else {
				
				if (!context.isAuthorizationActive())
					throw new SecurityException("Not authorized to preform export operation.");

				this.personId = null;
			}
			
			jobQueue.add(this);
		}
		
		/**
		 * Run the back ground job.
		 * 
		 * Iterate through the submissions and stream each one into the a zip
		 * archived. The archive will be buffered, and sent directly to the
		 * ChunkStream to be transmitted to the browser. This means we never
		 * have the complete archive on the server at any one time.
		 */
		public void doJob() throws IOException {

			try {

				if (personId != null) {
					Person person = personRepo.findPerson(personId);
					if (person == null)
						throw new IllegalStateException("Unable to complete deposit job because person no longer exists.");

					// Log the person in for this job.
					context.login(person);
				} else {
					// Assume we're running as a background admin process.
					context.turnOffAuthorization();
				}

				BufferedOutputStream bos = new BufferedOutputStream(out);
				ZipOutputStream zos = new ZipOutputStream(bos);
				String archiveFolder = packager.getBeanName()+File.separator;
				try {
					Iterator<Submission> itr = searcher.submissionSearch(filter, SearchOrder.ID, SearchDirection.ASCENDING);

					// Iterate over all the items, adding each one to the export.
					while (itr.hasNext()) {
						Submission sub = itr.next();

						ExportPackage pkg = packager.generatePackage(sub);
						try {
							String entryName = archiveFolder + "submission_" + sub.getId();
							if (pkg.getFile().isDirectory()) {
								zipDirectory(entryName + File.separator, pkg.getFile(), zos);
							} else {
								zipFile(entryName, pkg.getFile(), zos);
							}
						} finally {
							// Ensure the package isdeleted.
							pkg.delete();
						}
						
						// Don't let memory get out of control
						System.gc();
					}
				} finally {
					// Ensure the ziparchive is closed.
					try {
					zos.close();
					} catch (Exception e) {
						Logger.error(e,"Unable to close export zip archive, Ignoring.");
					}
				}

			} catch (RuntimeException re) {
				Logger.fatal(re,"Unexepcted exception while exporting items. Aborted.");
				throw re;

			} catch (IOException ioe) {
				Logger.error(ioe,"Unexpected expection while exporting items. Aborted.");
				throw ioe;

			} finally {
				// Clean up the security context
				if (personId != null) {
					context.logout();
				} else {
					context.restoreAuthorization();
				}
				jobQueue.remove(this);
			}
		}

		/**
		 * Zip a directory, and any sub directories that it may contain.
		 * 
		 * @param baseName
		 *            The base name for this directory.
		 * @param directory
		 *            The directory to include in the archive.
		 * @param zos
		 *            The archive's output stream.
		 */
		protected void zipDirectory(String baseName, File directory, ZipOutputStream zos) throws IOException
		{
			// Add all the files
			File[] files = directory.listFiles();
			for (File file : files) {

				if (file.isDirectory()) {

					zipDirectory(baseName + directory.getName() + File.separator, file, zos);
				} else {

					InputStream is = new BufferedInputStream(new FileInputStream(file));
					
					zos.putNextEntry(new ZipEntry(baseName + file.getName()));

					byte[] buf = new byte[1024];
					int len;
					while ((len = is.read(buf)) > 0) {
						zos.write(buf, 0, len);
					}

					is.close();
					zos.closeEntry();
				}	
			}
		}

		/**
		 * Zip a single file and include it in the archive. This method will use
		 * the baseName for the entry name, with the extension of the actual
		 * file. This is intended to be used at the top level of the export
		 * where we want everything to be "submission_#", but for single file
		 * exports it's nice to have the correct extension for the file type.
		 * 
		 * 
		 * @param baseName
		 *            The base name for this entry.
		 * @param file
		 *            The file to include in the archive.
		 * @param zos
		 *            The output stream for the zip archive.
		 */
		protected void zipFile(String baseName, File file, ZipOutputStream zos)
				throws IOException {
			// Add all the files		
			InputStream is = new BufferedInputStream(new FileInputStream(file));

			String extension = FilenameUtils.getExtension(file.getName());

			zos.putNextEntry(new ZipEntry(baseName+"."+extension));

			byte[] buf = new byte[1024];
			int len;
			while ((len = is.read(buf)) > 0) {
				zos.write(buf, 0, len);
			}

			is.close();
			zos.closeEntry();

		}
		
	}
	

}
