package classifier.workers;

import java.io.File;
import java.util.List;

import javax.swing.SwingWorker;

import classifier.core.Common;
import classifier.core.Console;
import classifier.core.HMMTestSeqResults;
import utils.DatasetUtils;
import utils.Observation;
import utils.SysUtils;

public class TestSequenceWorker extends SwingWorker<Integer, HMMTestSeqResults> {

	/** HMM file index */
	private final File[] sequencesFiles;
	private final Console console;
	private final boolean viterbi;

	/**
	 * Creates an instance of the worker
	 * 
	 * @param sequencesFiles An array of files containing sequences
	 */
	public TestSequenceWorker(final File[] sequencesFiles, Console console, boolean viterbi) {
		this.sequencesFiles = sequencesFiles;
		this.console = console;
		this.viterbi = viterbi;
	}

	@Override
	protected Integer doInBackground() throws Exception {
		synchronized (Common.loaded) {
			for (int i = 0; i < sequencesFiles.length; i++) {
				HMMTestSeqResults bestSolution = null;
				for (int j = 0; j < Common.modelsLoaded; j++) {
					Observation[] sequence = DatasetUtils.loadObservationsFromFile(sequencesFiles[i]);
					HMMTestSeqResults solution = new HMMTestSeqResults(Common.loaded.get(j).hmm.getModelName(),
							sequencesFiles[i].getName(), Common.loaded.get(j).hmm
									.likelihood(sequence));
					publish(solution);
					if(viterbi) {
						Common.loaded.get(j).hmm.viterbiToFile(sequence, new File(SysUtils.osFilePath(sequencesFiles[i].getParent(), Common.loaded.get(j).hmm.getModelName() + "_viterbi_" + sequencesFiles[i].getName())));
					}
					if (bestSolution == null || bestSolution.value > solution.value) {
						bestSolution = solution;
					}
				}
				publish(new HMMTestSeqResults(bestSolution.modelName, bestSolution.sequenceFileName, bestSolution.value,
						true));
			}
		}
		return 1;
	}

	@Override
	protected void process(List<HMMTestSeqResults> resultsList) {
		for (HMMTestSeqResults results : resultsList) {
			if (results.bestSolution) {
				console.addText(">>>> This sequence has been generated by " + results.modelName + " model <<<<");
			} else {
				console.addText(
						"log[P(" + results.sequenceFileName + "|" + results.modelName + ")] = " + results.value);
			}
		}
	}
}