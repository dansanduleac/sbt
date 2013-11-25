package sbt

import org.scalacheck._
import Prop._
import SettingsUsage._
import SettingsExample._

object SettingsTest extends Properties("settings")
{
	final val ChainMax = 5000
	lazy val chainLengthGen = Gen.choose(1, ChainMax)

	property("Basic settings test") = secure( all( tests: _*) )

	property("Basic chain") = forAll(chainLengthGen) { (i: Int) => 
		val abs = math.abs(i)
		singleIntTest( chain( abs, value(0)), abs )
	}
	property("Basic bind chain") = forAll(chainLengthGen) { (i: Int) =>
		val abs = math.abs(i)
		singleIntTest( chainBind(value(abs)), 0 )
	}

	property("Allows references to completed settings") = forAllNoShrink(30) { allowedReference }
	final def allowedReference(intermediate: Int): Prop =
	{
		val top = value(intermediate)
		def iterate(init: Initialize[Int]): Initialize[Int] =
			bind(init) { t =>
				if(t <= 0)
					top
				else
					iterate(value(t-1) )
			}
		try { evaluate( setting(chk, iterate(top)) :: Nil); true }
		catch { case e: java.lang.Exception => ("Unexpected exception: " + e) |: false }
	}

  lazy val der = AttributeKey[Int]("First derived setting")
  lazy val der1 = AttributeKey[Int]("Second derived setting")
  lazy val derk = ScopedKey( Scope(0), der)
  lazy val derk1 = ScopedKey( Scope(0), der1)

  property("Derives setting depending on another derived and a normal setting") = derivedSettings
  final def derivedSettings: Prop =
  {
    // Copied from main/settings/`sbt.Scoped`
    final class Apply2[A,B](t2: (Initialize[A], Initialize[B])) {
      def apply[T](z: (A,B) => T) = app[AList.T2K[A,B]#l, T]( t2 )( z.tupled )(AList.tuple2[A,B])
      def identity = apply(_ -> _)
    }
    val derivedSettings: Seq[Setting[Int]] =
    {
      val original = setting(derk, chk) // derk gets the value of chk
      val derk1init = new Apply2(derk -> chk).apply { (p, _) => p + 1 }
      original :: setting(derk1, derk1init) :: Nil
    } map { derive(_) }
    try {

      { checkKey(derk1, Some(1), evaluate(setting(chk, value(0)) +: derivedSettings)) :| "Not derived?" } &&
      { checkKey( derk1, None, evaluate(derivedSettings)) && checkKey( derk, None, evaluate(derivedSettings)) :|
        "Should not be derived" }
    }
    catch { case e: java.lang.Exception => ("Unexpected exception: " + e) |: false }
  }

// Circular (dynamic) references currently loop infinitely.
//  This is the expected behavior (detecting dynamic cycles is expensive),
//  but it may be necessary to provide an option to detect them (with a performance hit)
// This would test that cycle detection.
//	property("Catches circular references") = forAll(chainLengthGen) { checkCircularReferences _ }
	final def checkCircularReferences(intermediate: Int): Prop =
	{
		val ccr = new CCR(intermediate)
		try { evaluate( setting(chk, ccr.top) :: Nil); false }
		catch { case e: java.lang.Exception => true }
	}

	def tests = 
		for(i <- 0 to 5; k <- Seq(a, b)) yield {
			val expected = expectedValues(2*i + (if(k == a) 0 else 1))
			checkKey[Int]( ScopedKey( Scope(i), k ), expected, applied)
		}

	lazy val expectedValues = None :: None :: None :: None :: None :: None :: Some(3) :: None :: Some(3) :: Some(9) :: Some(4) :: Some(9) :: Nil

	lazy val ch = AttributeKey[Int]("ch")
	lazy val chk = ScopedKey( Scope(0), ch)
	def chain(i: Int, prev: Initialize[Int]): Initialize[Int] =
		if(i <= 0) prev else chain(i - 1, prev(_ + 1))

	def chainBind(prev: Initialize[Int]): Initialize[Int] =
		bind(prev) { v =>
			if(v <= 0) prev else chainBind(value(v - 1) )
		}
	def singleIntTest(i: Initialize[Int], expected: Int) =
	{
		val eval = evaluate( setting( chk, i ) :: Nil )
		checkKey( chk, Some(expected), eval )
	}

	def checkKey[T](key: ScopedKey[T], expected: Option[T], settings: Settings[Scope]) =
	{
		val value = settings.get( key.scope, key.key)
		("Key: " + key) |:
		("Value: " + value) |:
		("Expected: " + expected) |:
		(value == expected)
	}

	def evaluate(settings: Seq[Setting[_]]): Settings[Scope] =
		try { make(settings)(delegates, scopeLocal, showFullKey) }
		catch { case e: Throwable => e.printStackTrace; throw e }
}
// This setup is a workaround for module synchronization issues 
final class CCR(intermediate: Int)
{
	lazy val top = iterate(value(intermediate), intermediate)
	def iterate(init: Initialize[Int], i: Int): Initialize[Int] =
		bind(init) { t =>
			if(t <= 0)
				top
			else
				iterate(value(t - 1), t-1)
		}
}