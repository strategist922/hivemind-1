package twizansk.hivemind.drone;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import twizansk.hivemind.api.data.EmptyDataSet;
import twizansk.hivemind.api.data.TrainingSample;
import twizansk.hivemind.api.model.Gradient;
import twizansk.hivemind.api.model.Model;
import twizansk.hivemind.api.model.MsgUpdateModel;
import twizansk.hivemind.common.RemoteActor;
import twizansk.hivemind.common.StateMachine;
import twizansk.hivemind.common.Stay;
import twizansk.hivemind.common.Transition;
import twizansk.hivemind.drone.data.DataFetcher;
import twizansk.hivemind.messages.drone.MsgFetchNext;
import twizansk.hivemind.messages.drone.MsgGetInitialModel;
import twizansk.hivemind.messages.drone.MsgGetModel;
import twizansk.hivemind.messages.drone.MsgTrainingSample;
import twizansk.hivemind.messages.external.MsgConnectAndStart;
import twizansk.hivemind.messages.external.MsgReset;
import twizansk.hivemind.messages.external.MsgStop;
import twizansk.hivemind.messages.queen.MsgModel;
import twizansk.hivemind.messages.queen.MsgUpdateDone;
import akka.actor.ActorIdentity;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.dispatch.Mapper;
import akka.dispatch.OnSuccess;
import akka.pattern.Patterns;
import akka.util.Timeout;

/**
 * A Drone is an actor responsible for an atomic unit of work within the total
 * training process. Usually this means a single update step to the model.
 * Drones are meant to be (but don't have to be) colocated with the data they
 * are processing. Consequently, each drone is responsible for a subset of the
 * data.
 * 
 * @author Tommer Wizansky
 * 
 */
public final class Drone extends StateMachine {

	enum State {
		DISCONNECTED, CONNECTING, STOPPED, STARTING, ACTIVE
	}

	private final static Timeout timeout = new Timeout(Duration.create(5, "seconds"));
	private final ActorRef dataFetcher;
	private final DroneConfig config;
	
	private final RemoteActor queen;
	private final RemoteActor monitor;

	
	//////////////////////////////////////////////
	// Actions
	/////////////////////////////////////////////
	
	private final Action<Drone> CONNECT = new Action<Drone>() {

		@Override
		public void apply(Drone actor, Object message) {
			actor.queen.lookup();
			actor.monitor.lookup();
		}
		
	};  
	
	private final Action<Drone> GET_INITIAL_MODEL = new Action<Drone>() {

		@Override
		public void apply(Drone actor, Object message) {
			actor.queen.ref().tell(MsgGetModel.instance(), getSelf());
			getContext().system().scheduler().scheduleOnce(
					Duration.create(1, TimeUnit.SECONDS),
					getSelf(), 
					MsgGetInitialModel.instance(), 
					getContext().dispatcher(), 
					getSelf());
		}
		
	};  
	
	private final Action<Drone> START_TRAINING = new Action<Drone>() {

		@Override
		public void apply(Drone actor, Object message) {
			actor.prepareModelUpdateAndRespond(((MsgModel) message).model, actor.getSender());
		}
		
	};  
	
	private final Action<Drone> NEXT_UPDATE = new Action<Drone>() {

		@Override
		public void apply(Drone actor, Object message) {
			actor.prepareModelUpdateAndRespond(((MsgUpdateDone) message).currentModel, actor.getSender());
		}
		
	};  
	
	private final Action<Drone> RESET_DATASET = new Action<Drone>() {

		@Override
		public void apply(Drone actor, Object message) {
			actor.dataFetcher.tell(MsgReset.instance(), getSelf());
		}
		
	};  
	
	//////////////////////////////////////////////////////
	// Conditions on transitions
	/////////////////////////////////////////////////////
	
	private final Condition<Drone> IS_QUEEN_IDENTITY = new Condition<Drone>() {

		@Override
		public boolean isSatisfied(Drone actor, Object message) {
			return actor.config.queenPath.equals(
					((ActorIdentity)message).getRef().path().toString());
		}
		
	};
	
	private final Condition<Drone> IS_QUEEN_TERMINATED = new Condition<Drone>() {

		@Override
		public boolean isSatisfied(Drone actor, Object message) {
			return actor.config.queenPath.equals(((Terminated)message).actor().path().toString());
		}
		
	};
	
	public Drone(DroneConfig config) {
		this.config = config;
		this.queen = registerRemoteActor(config.queenPath);
		this.monitor = registerRemoteActor(config.monitorPath);
		this.state = State.DISCONNECTED;
		
		// Create supervised actors.
		this.dataFetcher = this.getContext().actorOf(DataFetcher.makeProps(config.trainingSet));
		config.trainingSet.reset();
		
		// Define the state machine
		this.addTransition(State.DISCONNECTED, MsgConnectAndStart.class, new Transition<>(State.CONNECTING, CONNECT));
		this.addTransition(State.CONNECTING, ActorIdentity.class, new Transition<>(State.STARTING, GET_INITIAL_MODEL, IS_QUEEN_IDENTITY));
		this.addTransition(State.STARTING, MsgGetInitialModel.class, new Transition<>(State.STARTING, GET_INITIAL_MODEL));
		this.addTransition(State.STARTING, MsgModel.class, new Transition<>(State.ACTIVE, START_TRAINING));
		this.addTransition(State.STARTING, MsgStop.class, new Transition<>(State.STOPPED));
		this.addTransition(State.ACTIVE, MsgUpdateDone.class, new Transition<>(State.ACTIVE, NEXT_UPDATE));
		this.addTransition(State.ACTIVE, MsgStop.class, new Transition<>(State.STOPPED));
		this.addTransition(State.STOPPED, MsgConnectAndStart.class, new Transition<>(State.STARTING, GET_INITIAL_MODEL));
		this.addTransition(State.STOPPED, MsgReset.class, new Stay<>(RESET_DATASET));
		this.addTransition(Terminated.class, new Transition<>(State.CONNECTING, IS_QUEEN_TERMINATED));
		
	}
	
	public static Props makeProps(DroneConfig config) {
		return Props.create(Drone.class, config);
	}
	
	/**
	 * Retrieves the next training sample, calculates the model update and responds with an {@link MsgUpdateModel} request.
	 * 
	 * @param model
	 *            The up-to-date model.
	 */
	private void prepareModelUpdateAndRespond(final Model model, final ActorRef target) {
		// Get the next training sample from the data set.
		final Future<Object> trainingSampleFuture = Patterns.ask(dataFetcher, MsgFetchNext.instance(), timeout);

		// Calculate the model update and prepare all the messages to be sent.
		Future<Map<Object, ActorRef>> future = trainingSampleFuture.map(new Mapper<Object, Map<Object, ActorRef>>() {

			@Override
			public Map<Object, ActorRef> apply(final Object trainingSampeObj) {
				if (trainingSampeObj instanceof EmptyDataSet) {
					throw new RuntimeException("Empty data set");
				}
				
				final TrainingSample sample = (TrainingSample) trainingSampeObj;
				
				// There two messages to be sent:  The UpdateModel message to the queen and the training sample to the monitoring actor.
				Map<Object, ActorRef> messages = new HashMap<Object, ActorRef>(2);
				messages.put(calculateModelUpdate(sample, model), target);
				
				if (monitor.isConnected()) {
					messages.put(new MsgTrainingSample(new Date(), sample), monitor.ref());
				}
				return messages; 
			}

		}, getContext().dispatcher());
		
		// If the data retrieval and calculation succeeded, send the required messages.
		future.onSuccess(new OnSuccess<Map<Object, ActorRef>>() {

			@Override
			public void onSuccess(Map<Object, ActorRef> messages) throws Throwable {
				for (Entry<Object, ActorRef> entry : messages.entrySet()) {
					entry.getValue().tell(entry.getKey(), getSelf());
				}
			}
		}, getContext().dispatcher());
		
	}

	/**
	 * Invokes the objective function to calculate the next update step to the
	 * model.
	 */
	private MsgUpdateModel calculateModelUpdate(TrainingSample sample, Model model) {
		Gradient gradient = this.config.objectiveFunction.singlePointGradient(sample, model);
		return MessageFactory.createUpdateModel(gradient);
	}
}
