package twizansk.hivemind.api.data;

/**
 * A configuration class that creates {@link TrainingSet} instances. This class
 * is meant to be provided by the user and holds information specific to the
 * implementation of the data storage: e.g. database connection details, file
 * system paths, etc.
 * 
 * @author Tommer Wizansky
 * 
 */
public interface DataConfig {

	public TrainingSet createTrainingSet();
	
}
