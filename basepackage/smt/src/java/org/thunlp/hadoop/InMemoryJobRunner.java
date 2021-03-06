package org.thunlp.hadoop;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.logging.Logger;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.mapred.Counters.Counter;
import org.thunlp.io.TextFileReader;
import org.thunlp.tool.FolderReader;
import org.thunlp.tool.FolderWriter;

/**
 * A in-memory MapReduce framework for SequenceFile Input/Output.
 * 
 * @author sixiance
 *
 */
public class InMemoryJobRunner {
	private static Logger LOG = Logger.getAnonymousLogger();

	public static class InMemoryCollector implements OutputCollector<Text, Text> {
		public Map<String, List<String>> results = new TreeMap<String, List<String>>();

		@Override
		public void collect(Text key, Text value) throws IOException {
			String ks = key.toString();
			List<String> values = results.get(ks);
			if (values == null) {
				values = new LinkedList<String>();
				results.put(ks, values);
			}
			values.add(value.toString());
		}

	}

	public static class SequenceFileCollector implements OutputCollector<Text, Text> {
		private FolderWriter writer = null;

		public SequenceFileCollector(Path p) throws IOException {
			writer = new FolderWriter(p, Text.class, Text.class);
		}

		@Override
		public void collect(Text key, Text value) throws IOException {
			writer.append(key, value);
		}

		public void close() throws IOException {
			writer.close();
		}
	}

	public static class DummyReporter implements Reporter {

		@Override
		public Counter getCounter(Enum<?> arg0) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public Counter getCounter(String arg0, String arg1) {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public InputSplit getInputSplit() throws UnsupportedOperationException {
			// TODO Auto-generated method stub
			return null;
		}

		@Override
		public void incrCounter(Enum<?> arg0, long arg1) {
			// TODO Auto-generated method stub

		}

		@Override
		public void incrCounter(String arg0, String arg1, long arg2) {
			// TODO Auto-generated method stub

		}

		@Override
		public void setStatus(String arg0) {
			// TODO Auto-generated method stub

		}

		@Override
		public void progress() {
			// TODO Auto-generated method stub

		}

	}

	public static class TextIterator implements Iterator<Text> {
		Iterator<String> values;
		Text container = new Text();

		public TextIterator(Iterator<String> values) {
			this.values = values;
		}

		@Override
		public boolean hasNext() {
			// TODO Auto-generated method stub
			return values.hasNext();
		}

		@Override
		public Text next() {
			container.set(values.next());
			return container;
		}

		@Override
		public void remove() {
			throw new RuntimeException("not implemented");
		}

	}

	public static boolean isMapOnly(JobConf job) {
		return job.getNumReduceTasks() == 0;
	}

	public static void runJob(JobConf job) throws IOException, InstantiationException, IllegalAccessException {
		LOG.info("In-memory MapReduce runner started.");
		Path output = new Path(job.get("mapred.output.dir"));

		LOG.info("Mapping");
		// Map phrase.
		Mapper mapper = (Mapper) job.getMapperClass().newInstance();
		mapper.configure(job);
		InMemoryCollector inMemCollector = null;
		SequenceFileCollector fileCollector = null;
		OutputCollector<Text, Text> mapCollector = null;
		if (isMapOnly(job)) {
			fileCollector = new SequenceFileCollector(output);
			mapCollector = fileCollector;
		} else {
			inMemCollector = new InMemoryCollector();
			mapCollector = inMemCollector;
		}

		Text key = new Text();
		LongWritable longkey = new LongWritable();
		Text value = new Text();
		for (String input : job.get("mapred.input.dir").split(",")) {
			if (job.getInputFormat() instanceof SequenceFileInputFormat) {
				FolderReader reader = new FolderReader(new Path(input));
				while (reader.next(key, value)) {
					mapper.map(key, value, mapCollector, new DummyReporter());
				}
				reader.close();
			} else if (job.getInputFormat() instanceof TextInputFormat) {
				TextFileReader reader = new TextFileReader(new Path(input), "UTF-8");
				String line;
				int n = 0;
				while ((line = reader.readLine()) != null) {
					longkey.set(n);
					value.set(line);
					mapper.map(longkey, value, mapCollector, new DummyReporter());
				}
				reader.close();
			}
		}
		mapper.close();
		if (isMapOnly(job)) {
			fileCollector.close();
		}
		LOG.info("Map done.");

		if (!isMapOnly(job)) {
			LOG.info("Reducing " + inMemCollector.results.size() + " groups.");
			// Reduce phrase.
			SequenceFileCollector reduceCollector = new SequenceFileCollector(output);
			Reducer reducer = (Reducer) job.getReducerClass().newInstance();
			reducer.configure(job);
			for (Entry<String, List<String>> e : inMemCollector.results.entrySet()) {
				key.set(e.getKey());
				TextIterator values = new TextIterator(e.getValue().iterator());
				reducer.reduce(key, values, reduceCollector, new DummyReporter());
			}
			reducer.close();
			reduceCollector.close();
			LOG.info("Reduce done.");
		}

	}
}
