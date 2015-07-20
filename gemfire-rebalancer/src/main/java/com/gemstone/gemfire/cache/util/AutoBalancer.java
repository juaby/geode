package com.gemstone.gemfire.cache.util;

import java.util.Date;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Logger;
import org.quartz.CronExpression;
import org.springframework.scheduling.support.CronSequenceGenerator;

import com.gemstone.gemfire.GemFireConfigException;
import com.gemstone.gemfire.cache.Declarable;
import com.gemstone.gemfire.cache.GemFireCache;
import com.gemstone.gemfire.cache.control.RebalanceOperation;
import com.gemstone.gemfire.cache.control.RebalanceResults;
import com.gemstone.gemfire.distributed.DistributedLockService;
import com.gemstone.gemfire.distributed.internal.locks.DLockService;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.logging.LogService;

/**
 * Re-balancing operation relocates data from heavily loaded members to lightly
 * loaded members. In most cases, the decision to re-balance is based on the
 * size of the member and a few other statistics. {@link AutoBalancer} monitors
 * these statistics and if necessary, triggers a re-balancing request.
 * Auto-Balancing is expected to prevent failures and data loss.
 * 
 * <P>
 * This implementation is based on {@code Initializer} implementation. By
 * default auto-balancing is disabled. A user needs to configure
 * {@link AutoBalancer} during cache initialization
 * {@link GemFireCache#getInitializer()}
 * 
 * <P>
 * In a cluster only one member owns auto-balancing responsibility. This is
 * achieved by grabbing a distributed lock. In case of a failure a new member
 * will grab the lock and manage auto balancing.
 * 
 * <P>
 * {@link AutoBalancer} can be controlled using the following configurations
 * <OL>
 * <LI> {@link AutoBalancer#SCHEDULE}
 * <LI>TBD THRESHOLDS
 * 
 * @author Ashvin Agrawal
 */
public class AutoBalancer implements Declarable {
  /**
   * Use this configuration to provide out-of-balance audit. If the audit finds
   * the system to be out-of-balance, it will trigger re-balancing. Any valid
   * cron string is accepted. The first value represent second.
   * <P>
   * For. e.g. {@code 0 0 * * * *} for auditing the system every hour
   */
  public static final String SCHEDULE = "schedule";

  /**
   * Name of the DistributedLockService that {@link AutoBalancer} will use to
   * guard against concurrent maintenance activity
   */
  public static final String AUTO_BALANCER_LOCK_SERVICE_NAME = "__AUTO_B";

  public static final Object AUTO_BALANCER_LOCK = "__AUTO_B_LOCK";

  private AuditScheduler scheduler = new CronScheduler();
  private OOBAuditor auditor = new SizeBasedOOBAuditor();
  private TimeProvider clock = new SystemClockTimeProvider();
  private CacheOperationFacade cacheFacade = new GeodeCacheFacade();

  private static final Logger logger = LogService.getLogger();

  @Override
  public void init(Properties props) {
    if (logger.isDebugEnabled()) {
      logger.debug("Initiazing " + this.getClass().getSimpleName() + " with " + props);
    }

    if (props != null) {
      String schedule = props.getProperty(SCHEDULE);

      auditor.init(props);
      scheduler.init(schedule);
    }
  }

  /**
   * Invokes audit triggers based on a cron schedule.
   * <OL>
   * <LI>computes delay = next slot - current time
   * <LI>schedules a out-of-balance audit task to be started after delay
   * computed earlier
   * <LI>once the audit task completes, it repeats delay computation and task
   * submission
   */
  private class CronScheduler implements AuditScheduler {
    final ScheduledExecutorService trigger;
    CronSequenceGenerator generator;

    CronScheduler() {
      trigger = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
          Thread thread = new Thread(r, "AutoBalancer");
          thread.setDaemon(true);
          return thread;
        }
      });
    }

    @Override
    public void init(String schedule) {
      if (logger.isDebugEnabled()) {
        logger.debug("Initiazing " + this.getClass().getSimpleName() + " with " + schedule);
      }

      if (schedule == null || schedule.isEmpty()) {
        throw new GemFireConfigException("Missing configuration: " + SCHEDULE);
      }
      if (!CronExpression.isValidExpression(schedule)) {
        throw new GemFireConfigException("Invalid schedule: " + schedule);
      }
      generator = new CronSequenceGenerator(schedule);

      submitNext();
    }

    private void submitNext() {
      long currentTime = clock.currentTimeMillis();
      Date nextSchedule = generator.next(new Date(currentTime));
      long delay = nextSchedule.getTime() - currentTime;

      if (logger.isDebugEnabled()) {
        logger.debug("Now={}, next audit time={}, delay={} ms", new Date(currentTime), nextSchedule, delay);
      }

      trigger.schedule(new Runnable() {
        @Override
        public void run() {
          try {
            auditor.execute();
          } catch (Exception e) {
            logger.warn("Error while executing out-of-balance audit.", e);
          }
          submitNext();
        }
      }, delay, TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Queries member statistics and health to determine if a re-balance operation
   * is needed
   * <OL>
   * <LI>acquires distributed lock
   * <LI>queries member health
   * <LI>updates auto-balance stat
   * <LI>release lock
   */
  class SizeBasedOOBAuditor implements OOBAuditor {
    @Override
    public void init(Properties props) {
      if (logger.isDebugEnabled()) {
        logger.debug("Initiazing " + this.getClass().getSimpleName());
      }
    }

    @Override
    public void execute() {
      cacheFacade.incrementAttemptCounter();
      boolean result = cacheFacade.acquireAutoBalanceLock();
      if (!result) {
        if (logger.isDebugEnabled()) {
          logger.debug("Another member owns auto-balance lock. Skip this attempt to rebalance the cluster");
        }
        return;
      }
      try {
        cacheFacade.rebalance();
      } finally {
        cacheFacade.releaseAutoBalanceLock();
      }
    }
  }

  /**
   * Hides cache level details and exposes simple methods relevant for
   * auto-balancing
   */
  static class GeodeCacheFacade implements CacheOperationFacade {
    @Override
    public void incrementAttemptCounter() {
      GemFireCacheImpl cache = getCache();
      try {
        cache.getResourceManager().getStats().incAutoRebalanceAttempts();
      } catch (Exception e) {
        logger.warn("Failed ot increment AutoBalanceAttempts counter");
      }
    }

    @Override
    public void rebalance() {
      RebalanceOperation operation = getCache().getResourceManager().createRebalanceFactory().simulate();
      try {
        RebalanceResults result = operation.getResults();
        logger.info(result.getTotalBucketTransfersCompleted() + " " + result.getTotalBucketTransferBytes());
      } catch (CancellationException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      } catch (InterruptedException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }

    private GemFireCacheImpl getCache() {
      GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      if (cache == null) {
        throw new IllegalStateException("Missing cache instance.");
      }
      return cache;
    }

    public boolean acquireAutoBalanceLock() {
      DistributedLockService dls = getDLS();

      boolean result = dls.lock(AUTO_BALANCER_LOCK, 0, -1);
      if (logger.isDebugEnabled()) {
        logger.debug("Grabed AutoBalancer lock? " + result);
      }
      return result;
    }

    public void releaseAutoBalanceLock() {
      DistributedLockService dls = getDLS();
      dls.unlock(AUTO_BALANCER_LOCK);
      if (logger.isDebugEnabled()) {
        logger.debug("Successfully release auto-balance ownership");
      }
    }

    @Override
    public DistributedLockService getDLS() {
      GemFireCacheImpl cache = getCache();
      DistributedLockService dls = DistributedLockService.getServiceNamed(AUTO_BALANCER_LOCK_SERVICE_NAME);
      if (dls == null) {
        if (logger.isDebugEnabled()) {
          logger.debug("Creating DistributeLockService");
        }
        dls = DLockService.create(AUTO_BALANCER_LOCK_SERVICE_NAME, cache.getDistributedSystem(), true, true, true);
      }

      return dls;
    }
  }

  private class SystemClockTimeProvider implements TimeProvider {
    @Override
    public long currentTimeMillis() {
      return System.currentTimeMillis();
    }
  }

  interface AuditScheduler {
    void init(String schedule);
  }

  interface OOBAuditor {
    void init(Properties props);

    void execute();
  }

  interface TimeProvider {
    long currentTimeMillis();
  }

  interface CacheOperationFacade {
    boolean acquireAutoBalanceLock();

    void releaseAutoBalanceLock();

    DistributedLockService getDLS();

    void rebalance();

    void incrementAttemptCounter();
  }

  /**
   * Test hook to inject custom triggers
   */
  void setScheduler(AuditScheduler trigger) {
    logger.info("Setting custom AuditScheduler");
    this.scheduler = trigger;
  }

  /**
   * Test hook to inject custom auditors
   */
  void setOOBAuditor(OOBAuditor auditor) {
    logger.info("Setting custom Auditor");
    this.auditor = auditor;
  }

  OOBAuditor getOOBAuditor() {
    return auditor;
  }

  /**
   * Test hook to inject a clock
   */
  void setTimeProvider(TimeProvider clock) {
    logger.info("Setting custom TimeProvider");
    this.clock = clock;
  }

  /**
   * Test hook to inject a Cache operation facade
   */
  public void setCacheOperationFacade(CacheOperationFacade facade) {
    this.cacheFacade = facade;
  }
}