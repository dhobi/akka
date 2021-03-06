/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import java.lang.management.ManagementFactory
import javax.management.InstanceNotFoundException
import javax.management.ObjectName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.util.Try

object MBeanMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
    akka.cluster.jmx.enabled = on
    """)).withFallback(MultiNodeClusterSpec.clusterConfig))

}

class MBeanMultiJvmNode1 extends MBeanSpec
class MBeanMultiJvmNode2 extends MBeanSpec
class MBeanMultiJvmNode3 extends MBeanSpec
class MBeanMultiJvmNode4 extends MBeanSpec

abstract class MBeanSpec
  extends MultiNodeSpec(MBeanMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MBeanMultiJvmSpec._
  import ClusterEvent._

  val mbeanName = new ObjectName("akka:type=Cluster")
  lazy val mbeanServer = ManagementFactory.getPlatformMBeanServer

  "Cluster MBean" must {
    "expose attributes" taggedAs LongRunningTest in {
      val info = mbeanServer.getMBeanInfo(mbeanName)
      info.getAttributes.map(_.getName).toSet should be(Set(
        "ClusterStatus", "Members", "Unreachable", "MemberStatus", "Leader", "Singleton", "Available"))
      enterBarrier("after-1")
    }

    "expose operations" taggedAs LongRunningTest in {
      val info = mbeanServer.getMBeanInfo(mbeanName)
      info.getOperations.map(_.getName).toSet should be(Set(
        "join", "leave", "down"))
      enterBarrier("after-2")
    }

    "change attributes after startup" taggedAs LongRunningTest in {
      runOn(first) {
        mbeanServer.getAttribute(mbeanName, "Available").asInstanceOf[Boolean] should be(false)
        mbeanServer.getAttribute(mbeanName, "Singleton").asInstanceOf[Boolean] should be(false)
        mbeanServer.getAttribute(mbeanName, "Leader") should be("")
        mbeanServer.getAttribute(mbeanName, "Members") should be("")
        mbeanServer.getAttribute(mbeanName, "Unreachable") should be("")
        mbeanServer.getAttribute(mbeanName, "MemberStatus") should be("Removed")
      }
      awaitClusterUp(first)
      runOn(first) {
        awaitAssert(mbeanServer.getAttribute(mbeanName, "MemberStatus") should be("Up"))
        awaitAssert(mbeanServer.getAttribute(mbeanName, "Leader") should be(address(first).toString))
        mbeanServer.getAttribute(mbeanName, "Singleton").asInstanceOf[Boolean] should be(true)
        mbeanServer.getAttribute(mbeanName, "Members") should be(address(first).toString)
        mbeanServer.getAttribute(mbeanName, "Unreachable") should be("")
        mbeanServer.getAttribute(mbeanName, "Available").asInstanceOf[Boolean] should be(true)
      }
      enterBarrier("after-3")
    }

    "support join" taggedAs LongRunningTest in {
      runOn(second, third, fourth) {
        mbeanServer.invoke(mbeanName, "join", Array(address(first).toString), Array("java.lang.String"))
      }
      enterBarrier("joined")

      awaitMembersUp(4)
      assertMembers(clusterView.members, roles.map(address(_)): _*)
      awaitAssert(mbeanServer.getAttribute(mbeanName, "MemberStatus") should be("Up"))
      val expectedMembers = roles.sorted.map(address(_)).mkString(",")
      awaitAssert(mbeanServer.getAttribute(mbeanName, "Members") should be(expectedMembers))
      val expectedLeader = address(roleOfLeader())
      awaitAssert(mbeanServer.getAttribute(mbeanName, "Leader") should be(expectedLeader.toString))
      mbeanServer.getAttribute(mbeanName, "Singleton").asInstanceOf[Boolean] should be(false)

      enterBarrier("after-4")
    }

    val fourthAddress = address(fourth)

    "format cluster status as JSON with full reachability info" taggedAs LongRunningTest in within(30 seconds) {
      runOn(first) {
        testConductor.exit(fourth, 0).await
      }
      enterBarrier("fourth-shutdown")

      runOn(first, second, third) {
        awaitAssert(mbeanServer.getAttribute(mbeanName, "Unreachable") should be(fourthAddress.toString))
        val expectedMembers = Seq(first, second, third, fourth).sorted.map(address(_)).mkString(",")
        awaitAssert(mbeanServer.getAttribute(mbeanName, "Members") should be(expectedMembers))
      }
      enterBarrier("fourth-unreachable")

      runOn(first) {
        val sortedNodes = Vector(first, second, third, fourth).sorted.map(address(_))
        val unreachableObservedBy = Vector(first, second, third).sorted.map(address(_))
        val expectedJson =
          s"""{
             |  "self-address": "${address(first)}",
             |  "members": [
             |    {
             |      "address": "${sortedNodes(0)}",
             |      "status": "Up"
             |    },
             |    {
             |      "address": "${sortedNodes(1)}",
             |      "status": "Up"
             |    },
             |    {
             |      "address": "${sortedNodes(2)}",
             |      "status": "Up"
             |    },
             |    {
             |      "address": "${sortedNodes(3)}",
             |      "status": "Up"
             |    }
             |  ],
             |  "unreachable": [
             |    {
             |      "node": "${address(fourth)}",
             |      "observed-by": [
             |        "${unreachableObservedBy(0)}",
             |        "${unreachableObservedBy(1)}",
             |        "${unreachableObservedBy(2)}"
             |      ]
             |    }
             |  ]
             |}
             |""".stripMargin

        // awaitAssert to make sure that all nodes detects unreachable
        within(15.seconds) {
          awaitAssert(mbeanServer.getAttribute(mbeanName, "ClusterStatus") should be(expectedJson))
        }
      }

      enterBarrier("after-5")

    }

    "support down" taggedAs LongRunningTest in within(20 seconds) {

      // fourth unreachable in previous step

      runOn(second) {
        mbeanServer.invoke(mbeanName, "down", Array(fourthAddress.toString), Array("java.lang.String"))
      }
      enterBarrier("fourth-down")

      runOn(first, second, third) {
        awaitMembersUp(3, canNotBePartOfMemberRing = Set(fourthAddress))
        assertMembers(clusterView.members, first, second, third)
        awaitAssert(mbeanServer.getAttribute(mbeanName, "Unreachable") should be(""))
      }

      enterBarrier("after-6")
    }

    "support leave" taggedAs LongRunningTest in within(20 seconds) {
      runOn(second) {
        mbeanServer.invoke(mbeanName, "leave", Array(address(third).toString), Array("java.lang.String"))
      }
      enterBarrier("third-left")
      runOn(first, second) {
        awaitMembersUp(2)
        assertMembers(clusterView.members, first, second)
        val expectedMembers = Seq(first, second).sorted.map(address(_)).mkString(",")
        awaitAssert(mbeanServer.getAttribute(mbeanName, "Members") should be(expectedMembers))
      }
      runOn(third) {
        awaitCond(cluster.isTerminated)
        // mbean should be unregistered, i.e. throw InstanceNotFoundException
        awaitAssert(intercept[InstanceNotFoundException] {
          mbeanServer.getMBeanInfo(mbeanName)
        })
      }

      enterBarrier("after-7")
    }

  }
}
