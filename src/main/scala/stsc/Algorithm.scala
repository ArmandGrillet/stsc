package stsc

import breeze.linalg.{DenseMatrix, DenseVector, argmax, csvwrite, eigSym, max, sum, svd, *}
import breeze.linalg.functions.euclideanDistance
import breeze.numerics.{abs, cos, pow, sin, sqrt}
import breeze.stats.mean

import scala.collection.immutable.SortedMap
import scala.util.control.Breaks.{break, breakable}
import java.io.File

/** Factory for gr.armand.stsc.Algorithm instances. */
object Algorithm {
    /** Cluster a given dataset using a self-tuning spectral clustering algorithm.
    *
    * @param dataset the dataset to cluster, each row being an observation with each column representing one dimension
    * @param minClusters the minimum number of clusters in the dataset
    * @param maxClusters the maximum number of clusters in the dataset
    * @return the best possible numer of clusters, a Map of costs (key = number of clusters, value = cost for this number of clusters) and the clusters for the best cost
    */
    def cluster(dataset: DenseMatrix[Double], minClusters: Int = 2, maxClusters: Int = 6): (Int, Map[Int, Double], DenseVector[Int]) = {
        // Three possible exceptions: empty dataset, minClusters less than 0, minClusters more than maxClusters.
        if (dataset.rows == 0) {
            throw new IllegalArgumentException("The dataset does not contains any observations.")
        }
        if (minClusters < 0) {
            throw new IllegalArgumentException("The minimum number of clusters has to be positive.")
        }
        if (minClusters > maxClusters) {
            throw new IllegalArgumentException("The minimum number of clusters has to be inferior to the maximum number of clusters.")
        }

        // Compute local scale (step 1).
        val distances = euclideanDistances(dataset)
        val scale = localScale(distances, 7) // In the original paper we use the 7th neighbor to create a local scale.

        // Build locally scaled affinity matrix (step 2).
        val scaledMatrix = locallyScaledAffinityMatrix(distances, scale)

        // Build the normalized affinity matrix (step 3)
        val normalizedMatrix = normalizedAffinityMatrix(scaledMatrix)

        // Compute the largest eigenvectors
        // val eigenvectors = eigSym(breeze.linalg.csvread(new File("./normalizedMatrix.csv"))).eigenvectors // Get the eigenvectors of the normalized affinity matrix.
        // val largestEigenvectors = DenseMatrix.tabulate(eigenvectors.rows, maxClusters) {
        //     case (i, j) => eigenvectors(i, -(1 + j)) // Reverses the matrix to get the largest eigenvectors only.
        // }
        //csvwrite(new File("./evs3.csv"), largestEigenvectors, separator = ',')
        //val largestEigenvectors = breeze.linalg.csvread(new File("./evs.csv"))
        val largestEigenvectors = svd(normalizedMatrix).leftVectors(::, 0 until maxClusters)

        var bestK = minClusters
        var costs: Map[Int, Double] = Map() // The costs, key = number of clusters and value = cost
        // The clusters, a dense vector where clusters(0) is the cluster where is the first observation.
        var currentEigenvectors = largestEigenvectors(::, 0 until minClusters) // We only take the eigenvectors needed for the number of clusters.
        var (cost, rotatedEigenvectors) = stsc(currentEigenvectors)
        costs += (minClusters -> cost) // Add the cost to the map.
        var absoluteRotatedEigenvectors = abs(rotatedEigenvectors)
        var clusters = argmax(absoluteRotatedEigenvectors(*, ::))

        var group = 0
        for (k <- minClusters until maxClusters) { // We get the cost of stsc for each possible number of clusters.
            val eigenvectorToAdd = largestEigenvectors(::, k).toDenseMatrix.t // One new eigenvector at each turn.
            currentEigenvectors = DenseMatrix.horzcat(rotatedEigenvectors, eigenvectorToAdd) // We add it to the already rotated eigenvectors.
            val (tempCost, tempRotatedEigenvectors) = stsc(currentEigenvectors)
            costs += (k + 1 -> tempCost) // Add the cost to the map.
            rotatedEigenvectors = tempRotatedEigenvectors // We keep the new rotation of the eigenvectors.

            if (tempCost <= cost * 1.0001) {
                absoluteRotatedEigenvectors = abs(rotatedEigenvectors)
                clusters = argmax(absoluteRotatedEigenvectors(*, ::))
                bestK = k + 1
            }
            if (tempCost < cost) {
                cost = tempCost
            }
        }

        val orderedCosts = SortedMap(costs.toSeq:_*) // Order the costs.
        return (bestK, orderedCosts, clusters)
    }

    /** Returns the euclidean distances of a given dense matrix.
    *
    * @param matrix the matrix that needs to be analyzed, each row being an observation with each column representing one dimension
    * @return the euclidean distances as a dense matrix
    */
    private[stsc] def euclideanDistances(matrix: DenseMatrix[Double]): DenseMatrix[Double] = {
        val distanceMatrix = DenseMatrix.zeros[Double](matrix.rows, matrix.rows) // Distance matrix, size rows x rows.

        var i, j = 0
        for (i <- 0 until matrix.rows) {
            for (j <- i + 1 until matrix.rows) {
                distanceMatrix(i, j) = euclideanDistance(matrix(i, ::).t, matrix(j, ::).t) // breeze.linalg.functions.euclideanDistance
                distanceMatrix(j, i) = distanceMatrix(i, j) // Symmetric matrix.
            }
        }

        return distanceMatrix
    }

    /** Returns the local scale as defined in the original paper, a vector containing the Kth nearest neighbor for each observation.
    *
    * @param distanceMatrix the distance matrix used to get the Kth nearest neighbor
    * @param k k, always 7 in the original paper
    * @return the local scale, the dictance of the Kth nearest neighbor for each observation as a dense vector
    */
    private[stsc] def localScale(distanceMatrix: DenseMatrix[Double], k: Int): DenseVector[Double] = {
        if (k > distanceMatrix.cols - 1) {
            throw new IllegalArgumentException("Not enough observations (" + distanceMatrix.cols + ") for k (" + k + ").")
        } else {
            var localScale = DenseVector.zeros[Double](distanceMatrix.cols)
            var sortedVector = IndexedSeq(0.0)

            var i = 0
            for (i <- 0 until distanceMatrix.cols) {
                sortedVector = distanceMatrix(::, i).toArray.sorted // Ordered distances.
                localScale(i) = sortedVector(k) // Kth nearest distance, the 0th neighbor is always 0 and sortedVector(1) is the first neighbor
            }

            return localScale
        }
    }

    /** Returns a locally scaled affinity matrix using a distance matrix and a local scale
    *
    * @param distanceMatrix the distance matrix
    * @param localScale the local scale, the dictance of the Kth nearest neighbor for each observation as a dense vector
    * @return the locally scaled affinity matrix
    */
    private[stsc] def locallyScaledAffinityMatrix(distanceMatrix: DenseMatrix[Double], localScale: DenseVector[Double]): DenseMatrix[Double] = {
        var affinityMatrix = DenseMatrix.zeros[Double](distanceMatrix.rows, distanceMatrix.cols) // Distance matrix, size rows x cols.

        var i, j = 0
        for (i <- 0 until distanceMatrix.rows) {
            for (j <- i + 1 until distanceMatrix.rows) {
                affinityMatrix(i, j) = -scala.math.pow(distanceMatrix(i, j), 2) // -d(si, sj)²
                affinityMatrix(i, j) /= (localScale(i) * localScale(j)) // -d(si, sj)² / lambi * lambj
                affinityMatrix(i, j) = scala.math.exp(affinityMatrix(i, j)) // exp(-d(si, sj)² / lambi * lambj)
                affinityMatrix(j, i) = affinityMatrix(i, j)
            }
        }

        return affinityMatrix
    }

    /** Returns the euclidean distance of a given dense matrix.
    *
    * @param scaledMatrix the matrix that needs to be normalized
    * @return the normalized matrix
    */
    private[stsc] def normalizedAffinityMatrix(scaledMatrix: DenseMatrix[Double]): DenseMatrix[Double] = {
        val diagonalVector = DenseVector.tabulate(scaledMatrix.rows){i => 1 / sqrt(sum(scaledMatrix(i, ::))) } // Sum of each row, then power -0.5.
        var normalizedMatrix = DenseMatrix.zeros[Double](scaledMatrix.rows, scaledMatrix.cols)

        for (row <- 0 until normalizedMatrix.rows) {
            for (col <- row + 1 until normalizedMatrix.cols) {
                normalizedMatrix(row, col) = diagonalVector(row) * scaledMatrix(row, col) * diagonalVector(col)
                normalizedMatrix(col, row) = normalizedMatrix(row, col)
            }
        }

        return normalizedMatrix
    }

    //
    /** Step 5 of the self-tuning spectral clustering algorithm, recovery the rotation R whiwh best align the eigenvectors.
    *
    * @param eigenvectors the eigenvectors
    * @return the cost of the best rotation and the linked dense matrix.
    */
    private[stsc] def stsc(eigenvectors: DenseMatrix[Double]): (Double, DenseMatrix[Double]) = {
        var nablaJ, cost = 0.0 // Variables used to recover the aligning rotation.
        var newCost, old1Cost, old2Cost = 0.0 // Variables to compute the descend through true derivative.
        var costUp, costDown = 0.0 // Variables to descend through numerical derivative.

        var rotatedEigenvectors = DenseMatrix.zeros[Double](0, 0)

        var theta, thetaNew = DenseVector.zeros[Double](angles(eigenvectors))

        cost = evaluateCost(eigenvectors)
        old1Cost = cost
        old2Cost = cost

        var i, j = 0
        breakable {
            for (i <- 1 to 200) { // Max iterations = 200, as in the original paper code.
                for (j <- 0 until angles(eigenvectors)) {
                    def numericalDerivative() {
                        val alpha = 0.1
                        // Move up.
                        thetaNew(j) = theta(j) + alpha
                        rotatedEigenvectors = rotateGivens(eigenvectors, thetaNew)
                        costUp = evaluateCost(rotatedEigenvectors)

                        // Move down.
                        thetaNew(j) = theta(j) - alpha
                        rotatedEigenvectors = rotateGivens(eigenvectors, thetaNew)
                        costDown = evaluateCost(rotatedEigenvectors)

                        // Update only if at least one of the new cost is better.
                        if (costUp < cost || costDown < cost) {
                            if (costUp < costDown) {
                                theta(j) = theta(j) + alpha
                                thetaNew(j) = theta(j)
                                cost = costUp
                            } else {
                                theta(j) = theta(j) - alpha
                                thetaNew(j) = theta(j)
                                cost = costDown
                            }
                        }
                    }

                    def trueDerivative() {
                        val alpha = 0.1
                        nablaJ = evaluateQualityGradient(theta, j, eigenvectors)
                        thetaNew(j) = theta(j) - alpha * nablaJ
                        rotatedEigenvectors = rotateGivens(eigenvectors, thetaNew)
                        newCost = evaluateCost(rotatedEigenvectors)

                        if (newCost < cost) {
                            theta(j) = thetaNew(j)
                            cost = newCost
                        } else {
                            thetaNew(j) = theta(j)
                        }
                    }

                    numericalDerivative()
                }

                // If the new cost is not that better, we end the rotation.
                if (i > 2 && (old2Cost - cost) < (0.0001 * old2Cost)) {
                    break
                }
                old2Cost = old1Cost
                old1Cost = cost
            }
        }

        // Last rotation
        //rotatedEigenvectors = rotateGivens(eigenvectors, thetaNew)

        // In rare cases the cost is Double.NaN, we handle this error here.
        if (cost equals Double.NaN) {
            return (0, rotatedEigenvectors)
        } else {
            return (cost, rotatedEigenvectors)
        }
    }

    /** Return the "angles" of a matrix, as defined in the original paper code.
    *
    * @param matrix the matrix to analyze
    * @return the angles
    */
    private[stsc] def angles(matrix: DenseMatrix[Double]): Int = {
        return (matrix.cols * (matrix.cols - 1) / 2).toInt
    }

    /** Return the cost of a given rotation, follow the computation in the original paper code.
    *
    * @param matrix the rotation to analyze
    * @return the cost, the bigger the better (generally less than 1)
    */
    private[stsc] def evaluateCost(matrix: DenseMatrix[Double]): Double = {
        // Take the square of all entries and find the max of each row
        var squareMatrix = matrix :* matrix
        return sum(sum(squareMatrix(*, ::)) / max(squareMatrix(*, ::))) // Sum of the sum of each row divided by the max of each row.
        // return 1.0 - (cost / matrix.rows - 1.0) / matrix.cols
    }

    private[stsc] def indexes(angles: Int, dims: Int): (DenseVector[Int], DenseVector[Int]) = {
        val ik = DenseVector.zeros[Int](angles)
        val jk = DenseVector.zeros[Int](angles)

        var i, j, k = 0
        for (i <- 0 until dims) {
            for (j <- (i + 1) until dims) {
                ik(k) = i
                jk(k) = j
                k += 1
            }
        }
        return (ik, jk)
    }

    private[stsc] def evaluateQualityGradient(theta: DenseVector[Double], angle: Int, matrix: DenseMatrix[Double]): Double = {
        val (ik, jk) = indexes(angles(matrix), matrix.cols)

        // Build V, U, A
        var vForAngle = DenseMatrix.zeros[Double](matrix.cols, matrix.cols)
        vForAngle(ik(angle),ik(angle)) = -sin(theta(angle))
        vForAngle(ik(angle),jk(angle)) = cos(theta(angle))
        vForAngle(jk(angle),ik(angle)) = -cos(theta(angle))
        vForAngle(jk(angle),jk(angle)) = -sin(theta(angle))
        val u1 = uAB(theta, 1, angle - 1, matrix.cols, angles(matrix))
        val u2 = uAB(theta, angle + 1, angles(matrix) -1, matrix.cols, angles(matrix))

        val a = matrix * u1 * vForAngle * u2

        val y = rotateGivens(matrix, theta)

        val maxValues = max(y(*, ::)) // Max of each row
        val maxIndexCol = argmax(y(*, ::))

        // Compute gradient
        var nablaJ, tmp1, tmp2 = 0.0
        for (i <- 0 until matrix.rows) { // Loop over all rows
            for (j <- 0 until matrix.cols) { // Loop over all columns
                tmp1 = a(i, j) * y(i, j) / (maxValues(i) * maxValues(i))
                tmp2 = a(i, maxIndexCol(i)) * pow(y(i, j), 2) / pow(maxValues(i), 3)
                nablaJ += tmp1 - tmp2
            }
        }
        nablaJ = 2 * nablaJ / matrix.rows / matrix.cols

        return nablaJ
    }

    /** Givens rotation of a given matrix
    *
    * @param matrix the matrix to rotate
    * @param theta the angle of the rotation
    * @return the Givens rotation
    */
    private[stsc] def rotateGivens(matrix: DenseMatrix[Double], theta: DenseVector[Double]): DenseMatrix[Double] = {
        val g = uAB(theta, 0, angles(matrix) - 1, matrix.cols, angles(matrix))
        return matrix * g
    }

    /** Build U(a,b) (check appendix A of the original paper for more info)
    *
    * @param theta the angle of the rotation
    * @param a
    * @param b
    * @param dims
    * @param angles
    * @return the gradient
    */
    private[stsc] def uAB(theta: DenseVector[Double], a: Int, b: Int, dims: Int, angles: Int): DenseMatrix[Double] = {
        var uab = DenseMatrix.eye[Double](dims) // Create an empty identity matrix.

        if (b < a) {
            return uab
        }

        val (ik, jk) = indexes(angles, dims)

        var tt, uIk = 0.0
        for (k <- a to b) {
            tt = theta(k)
            for (i <- 0 until dims) {
                uIk = uab(i, ik(k)) * cos(tt) - uab(i, jk(k)) * sin(tt)
                uab(i, jk(k)) = uab(i, ik(k)) * sin(tt) + uab(i, jk(k)) * cos(tt)
                uab(i, ik(k)) = uIk
            }
        }

        return uab
    }
}
