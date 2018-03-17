/*
 * Released under MIT License (Expat)
 * @author Luca Banzato
 * @version 0.1.8
 */

package baumwelch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

import exceptions.IllegalADefinitionException;
import exceptions.IllegalBDefinitionException;
import exceptions.IllegalPiDefinitionException;
import exceptions.IllegalStatesNamesSizeException;
import utils.Couple;
import utils.GaussianCurve;
import utils.Log;
import utils.SparseArray;
import utils.SparseMatrix;

public class BaumWelch {

	// This class implements

	private ContinuousModel currentModel; // The current model produced by BaumWelch
	private ArrayList<ObsSequence> sequences; // All the observation sequences
	private ArrayList<BWContainer> workingBench; // BWContainers that keep all the temporary models
	private double currentLikelihood; // The probability the model has to generate the sequences provided above
	private Logger logger = Log.getLogger();
	private int round = 0; // Denotes the round number of BW

	// Constructor without a model already prepared
	public BaumWelch(int nStates, String path, ArrayList<ObsSequence> sequences, boolean debug)
			throws IllegalPiDefinitionException, IllegalADefinitionException, IllegalBDefinitionException,
			IllegalStatesNamesSizeException {
		currentModel = new ContinuousModel(nStates, path);
		if (sequences != null && sequences.size() > 0) {
			this.sequences = sequences;
		} else {
			throw new IllegalArgumentException("At least one observations sequence must be provided!");
		}
	}

	// Constructor for a model already prepared
	public BaumWelch(ContinuousModel startModel, ArrayList<ObsSequence> sequences) {
		currentModel = startModel;
		if (sequences != null && sequences.size() > 0) {
			this.sequences = sequences;
		} else {
			throw new IllegalArgumentException("At least one observations sequence must be provided!");
		}
	}

	// Execute the BaumWelch algorithm
	public ContinuousModel step(String outputPath, boolean scaled, boolean debug)
			throws IllegalStatesNamesSizeException, IllegalADefinitionException, IllegalBDefinitionException,
			IllegalPiDefinitionException {

		if (debug) {
			logger.log(Level.INFO, "\n\n===== Round " + round + " ===== ");
		}

		double newLikelihood = 1.0; // Used for model evaluation

		if (round == 0) {
			currentModel.randomizePi(); // Randomize Pi and A (only at the beginning), read below for B
			currentModel.randomizeA();
			workingBench = new ArrayList<BWContainer>(); // Initializes a new "Working Bench" (ArrayList of
															// BWContainers)
			for (ObsSequence sequence : sequences) {
				workingBench.add(new BWContainer(currentModel.getNumberOfStates(), sequence.size()));
			}
			if (debug) {
				logger.log(Level.INFO, "Containers initialized");
				logger.log(Level.INFO, "PI and A randomized");
				logger.log(Level.INFO, "Pi: " + currentModel.getPi().toString());
				logger.log(Level.INFO, "A: \n" + currentModel.getA().toStringMatrix());
			}
		}
		for (int i = 0; i < sequences.size(); i++) {
			BWContainer container = workingBench.get(i); // Keeps all the A/B/Pi values in an object for each partial
															// model
			ObsSequence sequence = sequences.get(i);
			if (round == 0) {
				currentModel.randomizeB(sequence.getMean()); // Randomize Pi: Gaussians randomic values need to be
																// polarized to
																// the set (otherwise many 0 values are calculated by
																// the probability density function due to sigma and mu)
				if (debug) {
					logger.log(Level.INFO, "B: " + Arrays.toString(currentModel.getB()));
				}
			}
			Formula.alpha(currentModel, container, sequence, scaled, true); // Alpha
			newLikelihood *= container.getAlphaValue(); // Updates the likelihood of the sequences created by this model
			Formula.beta(currentModel, container, sequence, scaled, true); // Beta
			if (debug) {
				logger.log(Level.INFO,
						"\nAlpha Matrix obtained (from t=0 to T):\n" + container.getAlphaMatrix().toStringMatrix());
				logger.log(Level.INFO,
						"\nBeta Matrix obtained (from t=0 to T):\n" + container.getBetaMatrix().toStringMatrix());
				logger.log(Level.INFO, "\nCurrent Pi: " + currentModel.getPi().toString());
				logger.log(Level.INFO, "\nCurrent A:\n" + currentModel.getA().toString());
				logger.log(Level.INFO, "\n\n------ Re-estimation ------");
			}

			// BEGIN of Pi re-estimation
			piReestimation(container, debug);
			// END of Pi re-estimation

			// BEGIN of A re-estimation
			aReestimation(container, sequence, debug);
			// END of A re-estimation

			// BEGIN of B re-estimation
			bReestimation(container, sequence, debug);
			// END of B re-estimation
		}
		if (round > 0) { // likelihood of the test sequence must be evaluated with the previous one
			if (debug) {
				logger.log(Level.INFO,
						"New likelihood: " + newLikelihood + " | Current likelihood: " + currentLikelihood);
			}
		}
		currentLikelihood = newLikelihood; // Likelihood replaced
		currentModel = mergeModels(workingBench, currentModel.getNumberOfStates(), scaled, debug); // Model merging
		round++; // Round completed
		currentModel.writeToFiles(outputPath + "." + round); // Save all the models in files which name is
																// "example.round_number" (e.g.
																// test.26.trans/curves/start)
		System.gc(); // Garbage collection suggested
		return currentModel;
	}

	private void piReestimation(BWContainer container, boolean debug) {
		SparseArray pi = currentModel.getPi();
		SparseArray newPi = container.getPi();
		// Implementation of formula 40a (Pi(i) = gamma(1)(i))
		for (Couple cell : pi) {
			newPi.setToValue(cell.getX(), Formula.gamma(currentModel, container, 0, cell.getX(), false));
			if (debug) {
				logger.log(Level.INFO, "Value of new Pi(" + cell.getX() + ") = "
						+ Formula.gamma(currentModel, container, 0, cell.getX(), false) + "\n");
			}
		}
	}

	private void aReestimation(BWContainer container, ObsSequence sequence, boolean debug) {
		SparseMatrix a = currentModel.getA();
		SparseMatrix newA = container.getA();
		StringBuilder formulaLog = null;
		if (debug) {
			formulaLog = new StringBuilder();
			formulaLog.append("(");
		}
		int columnNumber = 0;
		// Implementation of formula 109, simplified as described on the errata (formula
		// 13)
		for (SparseArray column : a) {
			for (Couple cell : column) {
				double value = 0.0;
				double numerator = 0.0;
				for (int t = 0; t < sequence.size() - 2; t++) { // From 1 to T-1
					numerator += Formula.xi(currentModel, container, sequence, columnNumber, cell.getX(), t, false);
					if (debug) {
						formulaLog.append(
								Formula.xi(currentModel, container, sequence, columnNumber, cell.getX(), t, false)
										+ " + ");
					}
				}
				double denominator = 0.0;
				if (debug) {
					formulaLog.deleteCharAt(formulaLog.length() - 1);
					formulaLog.deleteCharAt(formulaLog.length() - 1);
					formulaLog.deleteCharAt(formulaLog.length() - 1);
					formulaLog.append(") / (");
				}
				for (int t = 0; t < sequence.size() - 2; t++) { // From 1 to T-1
					denominator += Formula.gamma(currentModel, container, t, columnNumber, false);
					if (debug) {
						formulaLog.append(Formula.gamma(currentModel, container, t, columnNumber, false) + " + ");
					}
				}
				if (debug) {
					formulaLog.deleteCharAt(formulaLog.length() - 1);
					formulaLog.deleteCharAt(formulaLog.length() - 1);
					formulaLog.deleteCharAt(formulaLog.length() - 1);
					formulaLog.append(")");
				}
				if (Double.compare(denominator, 0.0) == 0) {
					throw new ArithmeticException("Can't divide by zero");
				}
				value = numerator / denominator;
				if (debug) {
					logger.log(Level.INFO, "Value of new A(" + columnNumber + ")(" + cell.getX() + ") = "
							+ formulaLog.toString() + " = " + value);
					formulaLog.setLength(0);
				}
				newA.setToValue(columnNumber, cell.getX(), value);
			}
			columnNumber++;
		}
	}

	private void bReestimation(BWContainer container, ObsSequence sequence, boolean debug) {
		GaussianCurve[] b = currentModel.getB();
		GaussianCurve[] newB = container.getB();
		// Implementations of formula 53 and 54
		for (int j = 0; j < b.length; j++) {
			double muValue = 0.0;
			double sigmaValue = 0.0;
			double muNumerator = 0.0;
			double sigmaNumerator = 0.0;
			for (int t = 0; t < sequence.size(); t++) {
				muNumerator += Formula.gamma(currentModel, container, t, j, false) // Mu re-estimation, formula
																					// 53
						* sequence.getObservation(t);
				sigmaNumerator += Formula.gamma(currentModel, container, t, j, false) // Sigma re-estimation,
																						// formula 54
						* ((sequence.getObservation(t) - b[j].getMu()) * (sequence.getObservation(t) - b[j].getMu()));
			}
			// common denominator
			double denominator = 0.0;
			for (int t = 0; t < sequence.size(); t++) {
				denominator += Formula.gamma(currentModel, container, t, j, false);
			}
			if (Double.compare(denominator, 0.0) == 0) {
				throw new ArithmeticException("Can't divide by zero");
			}
			muValue = muNumerator / denominator;
			sigmaValue = sigmaNumerator / denominator;
			newB[j].setMu(muValue);
			newB[j].setSigma(sigmaValue);
		}
	}

	public ContinuousModel mergeModels(ArrayList<BWContainer> containers, int nStates, boolean scaled, boolean debug)
			throws IllegalStatesNamesSizeException, IllegalADefinitionException, IllegalBDefinitionException,
			IllegalPiDefinitionException {
		if (containers.size() == 0) {
			throw new IllegalArgumentException("There're no containers to merge, something is wrong!");
		}
		SparseArray newPi = null;
		SparseMatrix newA = new SparseMatrix(nStates, nStates);
		GaussianCurve[] newB = new GaussianCurve[nStates];
		for (int i = 0; i < newB.length; i++) {
			newB[i] = new GaussianCurve();
		}
		boolean first = true;
		for (BWContainer container : containers) {
			SparseMatrix a = container.getA();
			GaussianCurve[] b = container.getB();
			double alpha = container.getAlphaValue();

			// BEGIN of PI merging
			if (first) {
				newPi = container.getPi(); // Pi isn't merged, as stated on page 273 of Rabiner's paper
			}
			// END of PI merging

			// BEGIN of A merging
			mergeA(a, newA, alpha, debug);
			// END of A merging

			// BEGIN of B merging
			mergeB(b, newB, first, debug);
			// END of B merging
			if (first) {
				first = false;
			}
		}
		for (GaussianCurve curve : newB) {
			curve.setMu(curve.getMu() / nStates); // Arithmetic mean of the Gaussians parameters has to be done after B
													// merging
			curve.setSigma(curve.getSigma() / nStates);
		}
		return new ContinuousModel(nStates, newA, newB, newPi, currentModel.getStatesNames());
	}

	private void mergeA(SparseMatrix a, SparseMatrix newA, double alpha, boolean debug) {
		int columnNumber = 0;
		// Matrix addition (iterative, each time sums the values of newA and an A inside
		// a container
		for (SparseArray column : a) {
			for (Couple cell : column) {
				int x = columnNumber;
				int y = cell.getX();
				logger.log(Level.INFO, cell.getValue() + " " + alpha);
				newA.setToValue(x, y, newA.getValue(x, y) + a.getValue(x, y));
			}
			columnNumber++;
		}
	}

	private void mergeB(GaussianCurve[] b, GaussianCurve[] newB, boolean first, boolean debug) {
		// Parameters addition (iterative, each time sums the values of Mu and Sigma
		// of a container to the ones saved in newB
		for (int i = 0; i < b.length; i++) {
			if (first) {
				newB[i].setMu(b[i].getMu());
				newB[i].setSigma(b[i].getSigma());
			} else {
				newB[i].setMu(newB[i].getMu() + b[i].getMu());
				newB[i].setSigma(newB[i].getSigma() + b[i].getSigma());
			}
		}
		// NOTE: this is a partial formula, a division by the number of gaussians (=
		// mean) must be done
		// after the addition of all the gaussians paramteters
	}

	public int getCurrentRound() { // Returns the current round number
		return round;
	}

	public double getCurrentLikelihood() { // Returns the likelihood calculated after the latest BW step
		return currentLikelihood;
	}

	public ContinuousModel getCurrentModel() { // Returns the model calculated after the latest BW step
		return currentModel;
	}
}
