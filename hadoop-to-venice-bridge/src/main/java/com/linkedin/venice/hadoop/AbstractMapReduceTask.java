package com.linkedin.venice.hadoop;

import com.linkedin.venice.exceptions.UndefinedPropertyException;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.hadoop.ssl.SSLConfigurator;
import com.linkedin.venice.hadoop.ssl.UserCredentialsFactory;
import com.linkedin.venice.hadoop.utils.HadoopUtils;
import com.linkedin.venice.utils.VeniceProperties;
import java.io.IOException;
import java.util.Properties;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobID;
import org.apache.hadoop.mapred.TaskAttemptID;
import org.apache.hadoop.mapred.TaskID;

import static com.linkedin.venice.hadoop.KafkaPushJob.*;


/**
 * Class for commonalities between {@link AbstractVeniceMapper} and {@link VeniceReducer}.
 */
public abstract class AbstractMapReduceTask {
  public static final String MAPRED_TASK_ID_PROP_NAME = "mapred.task.id";
  protected static final int TASK_ID_NOT_SET = -1;

  private int partitionCount;
  private int taskId = TASK_ID_NOT_SET;

  abstract protected void configureTask(VeniceProperties props, JobConf job);

  protected int getPartitionCount() {
    return this.partitionCount;
  }

  protected int getTaskId() {
    return this.taskId;
  }

  public final void configure(JobConf job) {
    VeniceProperties props;

    SSLConfigurator configurator = SSLConfigurator.getSSLConfigurator(job.get(SSL_CONFIGURATOR_CLASS_CONFIG));
    try {
      Properties javaProps = configurator.setupSSLConfig(HadoopUtils.getProps(job), UserCredentialsFactory.getHadoopUserCredentials());
      props = new VeniceProperties(javaProps);
    } catch (IOException e) {
      throw new VeniceException("Could not get user credential for job:" + job.getJobName(), e);
    }

    this.partitionCount = job.getNumReduceTasks();
    TaskAttemptID taskAttemptID = TaskAttemptID.forName(job.get(MAPRED_TASK_ID_PROP_NAME));
    if (null == taskAttemptID) {
      throw new UndefinedPropertyException(MAPRED_TASK_ID_PROP_NAME + " not found in the " + JobConf.class.getSimpleName());
    }
    TaskID taskID = taskAttemptID.getTaskID();
    if (null == taskID) {
      throw new NullPointerException("taskAttemptID.getTaskID() is null");
    }
    this.taskId = taskID.getId();

    configureTask(props, job);
  }
}